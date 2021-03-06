package devbox.logger

import java.awt.event.{MouseEvent, MouseListener}
import devbox.common.{BaseLogger, Logger, PathSet, Util}
trait SyncLogger{
  def init(): Unit
  def close(): Unit
  def apply(tag: String, x: Any = Logger.NoOp): Unit
  def info(chunks: String*): Unit
  def error(chunks: String*): Unit
  def grey(chunks: String*): Unit
  def progress(chunks: String*): Unit
  def done(): Unit
  def syncingFile(chunkMsg: String, subPath: String, suffix: String): Unit
  def incrementFileTotal(base: os.Path, subs: PathSet): Unit
  def filesAndBytes(files: Long, bytes: Long): Unit
}

object SyncLogger{
  sealed trait Msg
  case class Init() extends Msg
  case class Close() extends Msg
  case class Apply(tag: String, x: Any) extends Msg
  case class Info(chunks: Seq[String]) extends Msg
  case class Error(chunks: Seq[String]) extends Msg
  case class Grey(chunks: Seq[String]) extends Msg
  case class Progress(chunks: Seq[String]) extends Msg
  case class Done() extends Msg
  case class SyncingFile(chunkMsg: String, subPath: String, suffix: String) extends Msg
  case class IncrementFileTotal(base: os.Path, subs: PathSet) extends Msg
  case class FilesAndBytes(files: Long, bytes: Long) extends Msg

  class Impl(val dest: String => os.Path,
             val rotationSize: Long,
             onClick: => castor.Actor[Unit],
             titleOpt: Option[String])
            (implicit ac: castor.Context) extends castor.SimpleActor[Msg] with SyncLogger {

    def init() = this.send(Init())

    override def close() = this.send(Close())
    def apply(tag: String, x: Any = Logger.NoOp): Unit = this.send(Apply(tag, x))
    def info(chunks: String*) = this.send(Info(chunks))
    def error(chunks: String*) = this.send(Error(chunks))
    def grey(chunks: String*) = this.send(Grey(chunks))
    def progress(chunks: String*) = this.send(Progress(chunks))

    def done() = this.send(Done())

    def filesAndBytes(files: Long, bytes: Long) = {
      this.send(FilesAndBytes(files, bytes))
    }
    def incrementFileTotal(base: os.Path, subs: PathSet) = {
      this.send(IncrementFileTotal(base, subs))
    }
    def syncingFile(chunkMsg: String, subPath: String, suffix: String): Unit = {
      this.send(SyncingFile(chunkMsg, subPath, suffix))
    }

    var closed = false
    var syncChanges = 0L
    var totalChanges = 0L
    var totalFiles = new PathSet()
    var syncBytes = 0L
    var chunkMsg = ""
    var path = ""
    var suffix = ""
    val consoleLogger = new ConsoleLogger(dest, rotationSize)
    val statusActor = new StatusActor(
      imageName => IconHandler.icon.setImage(IconHandler.images(imageName)),
      tooltip => IconHandler.setToolTip(fansi.Str(tooltip).plainText)
    )

    def run(msg: Msg) = if (!closed) msg match{
      case Init() => IconHandler.tray.add(IconHandler.icon)
      case Close() =>
        closed = true
        consoleLogger.send(Logger.Close())
        IconHandler.tray.remove(IconHandler.icon)

      case Apply(tag, x) => consoleLogger.send(Logger.PPrinted(tag, x))
      case Info(chunks) => logConsoleStatus("blue-sync", chunks)
      case Error(chunks) => logConsoleStatus("red-cross", chunks)
      case Grey(chunks) => logConsoleStatus("grey-dash", chunks)
      case Progress(chunks) => logConsoleStatus("blue-sync", chunks, progress = true)
      case Done() =>
        logConsoleStatus("green-tick", syncCompleteMsg(totalChanges, totalFiles, syncBytes))
        syncChanges = 0
        totalChanges = 0
        totalFiles = new PathSet()
        syncBytes = 0

      case SyncingFile(newChunkMsg, newPath, newSuffix) =>
        chunkMsg = newChunkMsg
        path = newPath
        suffix = newSuffix
        logSyncing()

      case IncrementFileTotal(base, subs) =>
        totalChanges += subs.size
        totalFiles = totalFiles.withPaths(subs.walk())
        logSyncing()

      case FilesAndBytes(files, bytes) =>
        syncBytes = syncBytes + bytes
        syncChanges = syncChanges + files
        logSyncing()
    }

    def logSyncing() = {
      logConsoleStatus(
        "blue-sync",
        Seq(s"Syncing path [$syncChanges/$totalChanges]$chunkMsg\n$path$suffix"),
        progress = true
      )
    }

    def logConsoleStatus(icon: String, chunks: Seq[String], progress: Boolean = false) = {
      consoleLogger.send(
        if (progress) Logger.Progress(chunks)
        else Logger.Info(chunks)
      )
      statusActor.send(StatusActor.SetIcon(icon, chunks))
    }

    def syncCompleteMsg(totalChanges: Long, totalFiles: PathSet, syncBytes: Long) = Seq(
      s"Syncing paths complete",
      s"${Util.formatInt(totalChanges)} changes to ${Util.formatInt(totalFiles.size)} paths, ${Util.readableBytesSize(syncBytes)}",
      s"${Util.timeFormatter.format(java.time.Instant.now())}"
    )

    object IconHandler{
      val images = Seq("blue-sync", "green-tick", "red-cross", "grey-dash")
        .map{name => (name, java.awt.Toolkit.getDefaultToolkit().getImage(getClass.getResource(s"/$name.png")))}
        .toMap

      val icon = new java.awt.TrayIcon(images("blue-sync"))

      setToolTip("Devbox Initializing")

      val tray = java.awt.SystemTray.getSystemTray()

      def setToolTip(s: String) =
        icon.setToolTip(titleOpt.map(_ + "\n").getOrElse("") + s)

      icon.addMouseListener(new MouseListener {
        def mouseClicked(e: MouseEvent): Unit = onClick.send(())
        def mousePressed(e: MouseEvent): Unit = ()
        def mouseReleased(e: MouseEvent): Unit = ()
        def mouseEntered(e: MouseEvent): Unit = ()
        def mouseExited(e: MouseEvent): Unit = ()
      })
    }

  }
}