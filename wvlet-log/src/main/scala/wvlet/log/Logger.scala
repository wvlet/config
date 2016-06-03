package wvlet.log

import java.util.concurrent.ConcurrentHashMap
import java.util.logging._
import java.util.{logging => jl}

import wvlet.log.LogFormatter.AppLogFormatter

import scala.annotation.tailrec
import scala.language.experimental.macros

/**
  * An wrapper of java.util.logging.Logger for supporting rich-format logging
  *
  * @param wrapped
  */
class Logger(wrapped: jl.Logger) {

  import LogMacros._

  def error(message: Any): Unit = macro errorLog
  def error(message: Any, cause: Throwable): Unit = macro errorLogWithCause

  def warn(message: Any): Unit = macro warnLog
  def warn(message: Any, cause: Throwable): Unit = macro warnLogWithCause

  def info(message: Any): Unit = macro infoLogMethod
  def info(message: Any, cause: Throwable): Unit = macro infoLogWithCause

  def debug(message: Any): Unit = macro debugLog
  def debug(message: Any, cause: Throwable): Unit = macro debugLogWithCause

  def trace(message: Any): Unit = macro traceLog
  def trace(message: Any, cause: Throwable): Unit = macro traceLogWithCause

  def getName = wrapped.getName

  def getLogLevel: LogLevel = {
    @tailrec
    def getLogLevelOf(l: jl.Logger): LogLevel = {
      if (l == null) {
        LogLevel.INFO
      }
      else {
        val jlLevel = l.getLevel
        if (jlLevel != null) {
          LogLevel(jlLevel)
        }
        else {
          getLogLevelOf(l.getParent)
        }
      }
    }
    getLogLevelOf(wrapped)
  }

  def setLogLevel(l: LogLevel) {
    wrapped.setLevel(l.jlLevel)
  }

  def resetHandler(h: Handler) {
    clearHandlers
    wrapped.addHandler(h)
    setUseParentHandlers(false)
  }

  def addHandler(h: Handler) {
    wrapped.addHandler(h)
  }

  def setUseParentHandlers(use: Boolean) {
    wrapped.setUseParentHandlers(use)
  }

  def clear {
    clearHandlers
    resetLogLevel
  }

  def clearHandlers {
    for (lst <- Option(wrapped.getHandlers); h <- lst) {
      wrapped.removeHandler(h)
    }
  }

  def resetLogLevel {
    wrapped.setLevel(null)
  }

  def isEnabled(level: LogLevel): Boolean = {
    wrapped.isLoggable(level.jlLevel)
  }

  def log(record: LogRecord) {
    record.setLoggerName(wrapped.getName)
    wrapped.log(record)
  }

  def log(level: LogLevel, source: LogSource, message: Any) {
    log(LogRecord(level, source, formatLog(message)))
  }

  def logWithCause(level: LogLevel, source: LogSource, message: Any, cause: Throwable) {
    log(LogRecord(level, source, formatLog(message), cause))
  }

  private def isMultiLine(str: String) = str.contains("\n")

  def formatLog(message: Any): String = {
    val formatted = message match {
      case null => ""
      case e: Error => LogFormatter.formatStacktrace(e)
      case e: Exception => LogFormatter.
                           formatStacktrace(e)
      case _ => message.toString
    }

    if (isMultiLine(formatted)) {
      s"\n${formatted}"
    }
    else {
      formatted
    }
  }

}

object Logger {

  import collection.JavaConverters._

  private val loggerCache = new ConcurrentHashMap[String, Logger].asScala

  val rootLogger = initLogger(
    name = "",
    handlers = Seq(new ConsoleLogHandler(AppLogFormatter)))

  /**
    * Create a new {@link java.util.logging.Logger}
    *
    * @param name
    * @param level
    * @param handlers
    * @param useParents
    * @return
    */
  def initLogger(name: String,
                level: Option[LogLevel] = None,
                handlers: Seq[Handler] = Seq.empty,
                useParents: Boolean = true
               ): Logger = {

    val logger = Logger.apply(name)
    logger.clearHandlers
    level.foreach(l => logger.setLogLevel(l))
    handlers.foreach(h => logger.addHandler(h))
    logger.setUseParentHandlers(useParents)
    logger
  }

  def apply(loggerName: String): Logger = {
    loggerCache.getOrElseUpdate(loggerName, new Logger(jl.Logger.getLogger(loggerName)))
  }

  def getDefaultLogLevel: LogLevel = rootLogger.getLogLevel

  def setDefaultLogLevel(level: LogLevel) {
    rootLogger.setLogLevel(level)
  }

  def setDefaultFormatter(formatter: LogFormatter) {
    rootLogger.resetHandler(new ConsoleLogHandler(formatter))
  }

  def resetDefaultLogLevel {
    rootLogger.resetLogLevel
  }
}

