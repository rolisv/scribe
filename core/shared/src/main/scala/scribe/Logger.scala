package scribe

import java.io.PrintStream

import scribe.format.Formatter
import scribe.handler.LogHandler
import scribe.modify.{LevelFilter, LogBooster, LogModifier}
import scribe.output.format.OutputFormat
import scribe.util.Time
import scribe.writer.{ConsoleWriter, Writer}

import scala.reflect._

case class Logger(parentId: Option[LoggerId] = Some(Logger.RootId),
                  modifiers: List[LogModifier] = Nil,
                  handlers: List[LogHandler] = Nil,
                  overrideClassName: Option[String] = None,
                  data: Map[String, () => Any] = Map.empty,
                  id: LoggerId = LoggerId()) extends LoggerSupport {
  lazy val isEmpty: Boolean = modifiers.isEmpty && handlers.isEmpty

  def reset(): Logger = copy(parentId = Some(Logger.RootId), Nil, Nil, None)
  def orphan(): Logger = copy(parentId = None)
  def withParent(name: String): Logger = copy(parentId = Some(Logger(name).id))
  def withParent(logger: Logger): Logger = copy(parentId = Some(logger.id))
  def withParent(id: LoggerId): Logger = copy(parentId = Some(id))
  def withHandler(handler: LogHandler): Logger = copy(handlers = handlers ::: List(handler))
  def withHandler(formatter: Formatter = Formatter.default,
                  writer: Writer = ConsoleWriter,
                  minimumLevel: Option[Level] = None,
                  modifiers: List[LogModifier] = Nil,
                  outputFormat: OutputFormat = OutputFormat.default): Logger = {
    withHandler(LogHandler(formatter, writer, minimumLevel, modifiers, outputFormat))
  }
  def withoutHandler(handler: LogHandler): Logger = copy(handlers = handlers.filterNot(_ == handler))
  def clearHandlers(): Logger = copy(handlers = Nil)
  def withClassNameOverride(className: String): Logger = copy(overrideClassName = Option(className))
  def setModifiers(modifiers: List[LogModifier]): Logger = copy(modifiers = modifiers.sorted)
  def clearModifiers(): Logger = setModifiers(Nil)
  def set(key: String, value: => Any): Logger = copy(data = this.data + (key -> (() => value)))
  def get(key: String): Option[Any] = data.get(key).map(_())

  final def withModifier(modifier: LogModifier): Logger = setModifiers(modifiers.filterNot(_.id == modifier.id) ::: List(modifier))
  final def withoutModifier(modifier: LogModifier): Logger = setModifiers(modifiers.filterNot(_.id == modifier.id))

  def includes(level: Level): Boolean = {
    modifierById[LevelFilter](LevelFilter.Id, recursive = true).forall(_.accepts(level.value))
  }

  def modifierById[M <: LogModifier](id: String, recursive: Boolean): Option[M] = {
    modifiers.find(_.id == id).orElse {
      parentId match {
        case _ if !recursive => None
        case None => None
        case Some(pId) => Logger(pId).modifierById(id, recursive)
      }
    }.map(_.asInstanceOf[M])
  }

  def withMinimumLevel(level: Level): Logger = withModifier(LevelFilter >= level)
  def withBoost(booster: Double => Double, priority: Priority = Priority.Normal): Logger = {
    withModifier(new LogBooster(booster, priority))
  }
  def withBoostOneLevel(): Logger = withBoost(_ + 100.0)
  def withBoosted(minimumLevel: Level, destinationLevel: Level): Logger = {
    withBoost(d => if (d >= minimumLevel.value && d <= destinationLevel.value) {
      destinationLevel.value
    } else {
      d
    })
  }

  override def log[M](record: LogRecord[M]): Unit = {
    val r = if (data.nonEmpty) {
      record.copy(data = data ++ record.data)
    } else {
      record
    }
    modifiers.foldLeft(Option(r)) {
      case (r, lm) => r.flatMap(_.modify(lm))
    }.foreach { r =>
      handlers.foreach(_.log(r))
      parentId.map(Logger.apply).foreach(_.log(r))
    }
  }

  def replace(name: Option[String] = None): Logger = name match {
    case Some(n) => Logger.replaceByName(n, this)
    case None => Logger.replace(this)
  }

  def logDirect[M](level: Level,
                   message: => M,
                   throwable: Option[Throwable] = None,
                   fileName: String = "",
                   className: String = "",
                   methodName: Option[String] = None,
                   line: Option[Int] = None,
                   column: Option[Int] = None,
                   thread: Thread = Thread.currentThread(),
                   timeStamp: Long = Time())
                  (implicit loggable: Loggable[M]): Unit = {
    log[M](LogRecord[M](
      level = level,
      value = level.value,
      message = new LazyMessage[M](() => message),
      loggable = loggable,
      throwable = throwable,
      fileName = fileName,
      className = className,
      methodName = methodName,
      line = line,
      column = column,
      thread = thread,
      timeStamp = timeStamp
    ))
  }
}

object Logger {
  // Keep a reference to the print streams just in case we need to redirect later
  private val systemOut = System.out
  private val systemErr = System.err

  object system {
    def out: PrintStream = systemOut
    def err: PrintStream = systemErr
  }

  val RootId: LoggerId = LoggerId(0L)

  private var id2Logger: Map[LoggerId, Logger] = Map.empty
  private var name2Id: Map[String, LoggerId] = Map.empty

  // Configure the root logger to filter anything under Info and write to the console
  root.orphan().withHandler(minimumLevel = Some(Level.Info)).replace(Some("root"))

  // Initialize Platform-specific functionality
  Platform.init()

  def empty: Logger = Logger()
  def root: Logger = apply(RootId)

  def loggersByName: Map[String, Logger] = name2Id.map {
    case (name, id) => name -> id2Logger(id)
  }

  def apply(name: String): Logger = get(name) match {
    case Some(logger) => logger
    case None => synchronized {
      val n = fixName(name)
      val dotIndex = n.lastIndexOf('.')
      val parentId = if (dotIndex > 0) {
        val parentName = n.substring(0, dotIndex)
        val parent = apply(parentName)
        parent.id
      } else {
        RootId
      }
      val logger = Logger(parentId = Some(parentId))
      id2Logger += logger.id -> logger
      name2Id += n -> logger.id
      logger
    }
  }

  def apply(id: LoggerId): Logger = get(id) match {
    case Some(logger) => logger
    case None => synchronized {
      val logger = new Logger(id = id)
      id2Logger += logger.id -> logger
      logger
    }
  }

  def minimumLevels(minimums: MinimumLevel*): Unit = minimums.foreach { m =>
    m.logger.withMinimumLevel(m.minimumLevel).replace()
  }

  def apply[T](implicit t: ClassTag[T]): Logger = apply(t.runtimeClass.getName)

  def get(name: String): Option[Logger] = name2Id.get(fixName(name)).flatMap(id2Logger.get)

  def get(id: LoggerId): Option[Logger] = id2Logger.get(id)

  def get[T](implicit t: ClassTag[T]): Option[Logger] = get(t.runtimeClass.getName)

  def replace(logger: Logger): Logger = synchronized {
    id2Logger += logger.id -> logger
    logger
  }

  def replaceByName(name: String, logger: Logger): Logger = synchronized {
    replace(logger)
    name2Id += fixName(name) -> logger.id
    logger
  }

  def namesFor(loggerId: LoggerId): Set[String] = name2Id.collect {
    case (name, id) if id == loggerId => name
  }.toSet

  private def fixName(name: String): String = name.replace("$", "")
}