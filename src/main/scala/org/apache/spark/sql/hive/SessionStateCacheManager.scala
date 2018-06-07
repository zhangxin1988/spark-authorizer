/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive

import java.io.File
import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}

import scala.collection.JavaConverters._

import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.ql.session.SessionState

import org.apache.spark.SparkConf
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.sql.Logging
import org.apache.spark.util.{ShutdownHookManager, Utils}

class SessionStateCacheManager(conf: SparkConf) extends Logging {

  private[this] val cacheManager =
    Executors.newSingleThreadScheduledExecutor(
      new ThreadFactoryBuilder()
        .setDaemon(true).setNameFormat(getClass.getSimpleName + "-%d").build())

  private[this] val userToState = new ConcurrentHashMap[String, SessionState]

  private[this] val userLastActive = new ConcurrentHashMap[String, Long]

  private[this] def currentTime: Long = System.currentTimeMillis()

  private[this] val hiveConf: HiveConf = {
    val hadoopConf = SparkHadoopUtil.get.newConfiguration(conf)
    Seq("hive-site.xml", "ranger-hive-security.xml", "ranger-hive-audit.xml.xml")
      .foreach { file =>
        Option(Utils.getContextOrSparkClassLoader.getResource(file)).foreach(hadoopConf.addResource)
      }

    val dir = hadoopConf.get("ranger.plugin.hive.policy.cache.dir")
    if (dir != null) {
      val file = new File(dir)
      if (!file.exists()) {
        if (file.mkdirs()) {
          info("Creating ranger policy cache directory at " + file.getAbsolutePath)
          file.deleteOnExit()
        } else {
          warn("Unable to create ranger policy cache directory at " + file.getAbsolutePath)
        }
      } else {
        warn("Ranger policy cache directory already exists")
      }
    }
    val c = new HiveConf(hadoopConf, classOf[SessionState])
    c.setBoolVar(HiveConf.ConfVars.HIVE_SESSION_HISTORY_ENABLED, false)
    c
  }

  /**
   * Get SPARK_USER
   */
  private[this] def currentUser: String = Utils.getCurrentUserName()

  private[this] val stateCleaner = new Runnable {
    val timeout: Long = conf.getLong("spark.sql.authorizer.state.timeout", 60 * 60 * 1000L)
    override def run(): Unit = {
      userToState.asScala.foreach {
        case (user, state) =>
          val lastActive = userLastActive.getOrDefault(user, currentTime)
          val idled = currentTime - lastActive
          if (idled > timeout) {
            info("")
            userToState.remove(user)
            state.close()
            userLastActive.remove(user)
          }
        case _ =>
      }
    }
  }

  def getState: SessionState = {
    val user = currentUser
    userLastActive.put(user, currentTime)
    val state = userToState.get(user)
    if (state != null) {
      state
    } else {
      newState(user)
    }
  }

  /**
   * Create a Hive [[SessionState]]
   * @return
   */
  private[this] def newState(user: String): SessionState = {
    try {
      val state = new SessionState(hiveConf, user)
      SessionState.start(state)
      state.setIsHiveServerQuery(true)
      userToState.put(user, state)
    } catch {
      case e: RuntimeException =>
        error("Failed to initialize SessionState for authorization", e.getCause)
        null
    }
  }

  def start(): Unit = {
    val interval: Int = 60
    if (conf.getBoolean("spark.sql.authorizer.state.clean.enable", true)) {
      info(s"Scheduling SessionState cache cleaning every $interval seconds")
      cacheManager.scheduleAtFixedRate(stateCleaner, interval, interval, TimeUnit.SECONDS)
    }
    ShutdownHookManager.addShutdownHook(() => this.stop())
  }

  def stop(): Unit = {
    info("Stopping SessionState Cache Manager")
    cacheManager.shutdown()
    userToState.asScala.values.foreach(_.close())
    userToState.clear()
    userLastActive.clear()
  }
}

object SessionStateCacheManager {
  private[this] var manager: SessionStateCacheManager = _

  def startCacheManager(conf: SparkConf): Unit = {
    manager = new SessionStateCacheManager(conf)
    manager.start()
  }

  def get(): SessionStateCacheManager = manager
}
