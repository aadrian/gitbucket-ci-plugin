package io.github.gitbucket.ci.service

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

import gitbucket.core.controller.Context
import gitbucket.core.model.CommitState
import gitbucket.core.model.Session
import gitbucket.core.service.{AccountService, CommitStatusService}
import gitbucket.core.servlet.Database
import gitbucket.core.util.Directory.getRepositoryDir
import gitbucket.core.util.SyntaxSugars.using
import gitbucket.core.model.Profile.profile.blockingApi._
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.exception.ExceptionUtils
import org.eclipse.jgit.api.Git

import scala.sys.process.Process
import scala.util.control.ControlThrowable


class BuildJobThread(queue: LinkedBlockingQueue[BuildJob]) extends Thread with CommitStatusService with AccountService {

  val killed = new AtomicReference[Boolean](false)
  val runningProcess = new AtomicReference[Option[Process]](None)
  val runningJob = new AtomicReference[Option[BuildJob]]()
  val sb = new StringBuffer()

  override def run(): Unit = {
    while(true){
      val job = queue.take()
      initState(Some(job))
      runBuild(job)
      initState(None)
    }
  }

  private def initState(job: Option[BuildJob]): Unit = {
    killed.set(false)
    runningProcess.set(None)
    runningJob.set(job)
    sb.setLength(0)
  }

  private def runBuild(job: BuildJob): Unit = {
    val startTime = System.currentTimeMillis

    val exitValue = try {
      val dir = new File(s"/tmp/${job.userName}-${job.repositoryName}-${job.buildNumber}")
      if (dir.exists()) {
        FileUtils.deleteDirectory(dir)
      }

      if(killed.get() == true){
        throw new BuildJobKillException()
      }

      // git clone
      using(Git.cloneRepository()
        .setURI(getRepositoryDir(job.userName, job.repositoryName).toURI.toString)
        .setDirectory(dir).call()) { git =>

        if(killed.get() == true){
          throw new BuildJobKillException()
        }

        // git checkout
        git.checkout().setName(job.sha).call()

        if(killed.get() == true){
          throw new BuildJobKillException()
        }

        // run script
        val process = Process(job.setting.script, dir).run(new BuildProcessLogger(sb))
        runningProcess.set(Some(process))

        while (process.isAlive()) {
          Thread.sleep(1000)
        }

        process.exitValue()
      }
    } catch {
      case e: Exception => {
        sb.append(ExceptionUtils.getStackTrace(e))
        e.printStackTrace()
        -1
      }
      case e: ControlThrowable =>
        -1
    }

    val endTime = System.currentTimeMillis

    val result = BuildResult(job.userName, job.repositoryName, job.sha, job.buildNumber, exitValue == 0, startTime, endTime, sb.toString)

    // TODO This should be implemented in the BuildManager side?
    val results = Option(BuildManager.buildResults.get((job.userName, job.repositoryName))).getOrElse(Nil)
    BuildManager.buildResults.put((job.userName, job.repositoryName),
      (if (results.length >= BuildManager.MaxBuildsPerProject) results.tail else results) :+ result
    )

    // Create or update commit status
    Database() withTransaction { implicit session =>
      createCommitStatus(
        userName       = job.userName,
        repositoryName = job.repositoryName,
        sha            = job.sha,
        context        = "gitbucket-ci",
        state          = (if(exitValue == 0) CommitState.SUCCESS else CommitState.FAILURE),
        targetUrl      = None,
        description    = None,
        now            = new java.util.Date(endTime),
        creator        = getAccountByUserName("root").get // TODO
      )
    }

    println("Build number: " + job.buildNumber)
    println("Total: " + (endTime - startTime) + "msec")
    println("Finish build with exit code: " + exitValue)
  }

  def kill(): Unit = {
    killed.set(true)
    runningProcess.get.foreach(_.destroy())
  }
}

// Used to abort build job immediately in BuildJobThread
private class BuildJobKillException extends ControlThrowable
