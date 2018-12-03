package devbox

import java.io._
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

import devbox.common.{Logger, RpcClient}

/**
  * In-memory implementation of [[AgentApi]], so we can run unit tests on the
  * without paying the expensive JVM startup cost.
  */
class InMemoryAgent(dest: os.Path, skip: (os.Path, os.Path) => Boolean) extends AgentApi {
  var alive = true
  def isAlive() = alive

  def destroy(): Unit = {
    stderr0.close()
    stdout0.close()
    stdin0.close()
    thread.interrupt()
    thread.join()
    alive = false
  }
  val (stderr0, stderrWrite) = InMemoryAgent.createPipedStreams()
  val stderr = new DataInputStream(stderr0)

  val (stdout0, stdoutWrite) = InMemoryAgent.createPipedStreams()
  val stdout = new DataInputStream(stdout0)

  val (stdinRead, stdin0) = InMemoryAgent.createPipedStreams()
  val stdin = new DataOutputStream(stdin0)

  val thread = new Thread(() =>
    try devbox.agent.Agent.mainLoop(
      new Logger{
        def write(s: String): Unit = synchronized{
          stderrWrite.write((ujson.write(s) + "\n").getBytes())
          stderrWrite.flush()
        }
        def close() = () // do nothing
      },
      skip,
      new RpcClient(new DataOutputStream(stdoutWrite), new DataInputStream(stdinRead)),
      dest
    ) catch{
      case e: java.lang.InterruptedException => () // do nothing
    },
    "DevboxMockAgentThread"
  )

  thread.start()
}

object InMemoryAgent{
  /**
    * Creates a pair of piped streams with a fixed buffer in between, but
    * without the weird "read end dead"/"write end dead" errors that turn up
    * when trying to use [[java.io.PipedInputStream]]/[[java.io.PipedOutputStream]]
    */
  def createPipedStreams(bufferSize: Int = 1024) = {
    val buffer = new Array[Byte](bufferSize)
    val availableWrite = new Semaphore(bufferSize)
    val availableRead = new Semaphore(0)
    val writeIndex = new AtomicLong()
    val readIndex = new AtomicLong()
    val out = new OutputStream {
      def write(b: Int) = {
        availableWrite.acquire(1)
        val i = writeIndex.getAndIncrement()
        buffer((i % bufferSize).toInt) = b.toByte

        availableRead.release(1)
      }

      override def write(b: Array[Byte]): Unit = synchronized{ write(b, 0, b.length) }

      override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized{
        var n = 0
        while(n < len){
          availableWrite.acquire()
          val allPermits = availableWrite.drainPermits() + 1
          val writeStart = (writeIndex.get() % bufferSize).toInt
          val numToWrite = math.min(allPermits, len - n)
          if (writeStart + numToWrite <= bufferSize){
            System.arraycopy(b, off + n, buffer, writeStart, numToWrite)
          }else{
            val firstHalfLength = bufferSize - writeStart
            System.arraycopy(b, off + n, buffer, writeStart, firstHalfLength)
            System.arraycopy(b, off + n + firstHalfLength, buffer, 0, numToWrite - firstHalfLength)
          }
          n += numToWrite
          writeIndex.addAndGet(numToWrite)
          availableWrite.release(allPermits - numToWrite)
          availableRead.release(numToWrite)
        }
      }
    }

    val in = new InputStream {
      def read() = {
        availableRead.acquire(1)
        val i = readIndex.getAndIncrement()
        val res = buffer((i % bufferSize).toInt) & 0xff

        availableWrite.release(1)
        res
      }

      override def read(b: Array[Byte]) = synchronized{ read(b, 0, b.length) }

//      override def read(b: Array[Byte], off: Int, len: Int) = synchronized{
//
//      }
    }
    (in, out)
  }
}