package wvlet.helix

import java.io.{PrintStream, PrintWriter}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import javax.inject.{Inject, Singleton}

import wvlet.log.LogSupport
import wvlet.obj.ObjectType
import wvlet.test.WvletSpec

import scala.util.Random

case class ExecutorConfig(numThreads: Int)

object ServiceMixinExample {

  trait Printer {
    def print(s: String): Unit
  }

  case class ConsoleConfig(out:PrintStream)

  class ConsolePrinter(config:ConsoleConfig) extends Printer with LogSupport {
    info(s"using config: ${config}")

    def print(s: String) { config.out.println(s) }
  }

  class LogPrinter extends Printer with LogSupport {
    def print(s: String) {info(s)}
  }

  class Fortune {
    def generate: String = {
      val pattern = Seq("Hello", "How are you?")
      pattern(Random.nextInt(pattern.length))
    }
  }

  trait PrinterService {
    protected def printer = inject[Printer]
  }

  trait FortuneService {
    protected def fortune = inject[Fortune]
  }

  /**
    * Mix-in printer/fortune instances
    * Pros:
    * -
    * Cons:
    *   - Need to define XXXService boilerplate, which just has a val or def of the service object
    *   - Cannot change the variable name without defining additional XXXService trait
    *     - Need to care about variable naming conflict
    *   - We don't know the missing dependenncy at compile time
    */
  trait FortunePrinterMixin extends PrinterService with FortuneService {
    printer.print(fortune.generate)
  }

  /**
    * Using local val/def injection
    *
    * Pros:
    *   - Service reference (e.g., printer, fortune) can be scoped inside the trait.
    *   - You can save boilerplate code
    * Cons:
    *   - If you use the same service multiple location, you need to repeat the same description in the user module
    *   - To reuse it in other traits, we still need to care about the naming conflict
    */
  trait FortunePrinterEmbedded {
    protected def printer = inject[Printer]
    protected def fortune = inject[Fortune]

    printer.print(fortune.generate)
  }

  /**
    * Using Constructor for dependency injection (e.g., Guice)
    *
    * Pros:
    *   - Close to the traditional OO programming style
    *   - Can be used without DI framework
    * Cons:
    *   - To add/remove modules, we need to create another constructor or class.
    * -> code duplication occurs
    *   - Enhancing the class functionality
    *   - RememenbOrder of constructor arguments
    * -
    */
  class FortunePrinterAsClass @Inject() (printer: Printer, fortune: Fortune) {
    printer.print(fortune.generate)
  }

  import com.softwaremill.macwire._

//  /**
//    * Using macwire
//    */
//  trait FortunePrinterWired {
//    lazy val printer = wire[Printer] // macwire cannot specify dynamic binding
//    lazy val fortune = wire[Fortune]
//
//    printer.print(fortune.generate)
//  }

  class HeavyObject() extends LogSupport {
    info(f"Heavy Process!!: ${this.hashCode()}%x")
  }

  trait HeavyService {
    val heavy = wire[HeavyObject]
  }

  trait AppA extends HeavyService {

  }

  trait AppB extends HeavyService {

  }

  trait HeavySingletonService {
    val heavy = inject[HeavyObject]
  }

  trait HelixAppA extends HeavySingletonService {
  }

  trait HelixAppB extends HeavySingletonService {
  }

  case class A(b:B)
  case class B(a:A)


  class EagerSingleton extends LogSupport {
    info("initialized")
    val initializedTime = System.nanoTime()
  }

  class ClassWithContext(val c:Context) extends FortunePrinterMixin with LogSupport {
    //info(s"context ${c}") // we should access context since Scala will remove private field, which is never used
  }

  case class HelloConfig(message:String)

  class FactoryExample(val c:Context) {
    val hello = inject { config:HelloConfig => s"${config.message}" }
    val hello2 = inject { (c1:HelloConfig, c2:EagerSingleton) => s"${c1.message}:${c2.getClass.getSimpleName}" }

    val helloFromProvider = inject(provider _)

    def provider(config:HelloConfig) : String = config.message
  }


}

/**
  *
  */
class HelixTest extends WvletSpec {

  import wvlet.helix.ServiceMixinExample._

  "Helix" should {

    "instantiate class" in {

      val h = new Helix
      h.bind[Printer].to[ConsolePrinter]
      h.bind[ConsoleConfig].toInstance(ConsoleConfig(System.err))

      val context = h.newContext
      val m = context.weave[FortunePrinterMixin]
    }

    "test macwire example" in {
      //val w = new FortunePrinterWired {}

      new AppA {}
      new AppB {}
    }

    "create singleton" in {
      val h = new Helix
      h.bind[HeavyObject].asSingleton

      val c = h.newContext
      val a = c.weave[HelixAppA]
      val b = c.weave[HelixAppB]
      a.heavy shouldEqual b.heavy
    }

    "create singleton eagerly" in {
      val start = System.nanoTime()
      val h = new Helix
      h.bind[EagerSingleton].asEagerSingleton
      val c = h.newContext
      c.get[HeavyObject]
      val current = System.nanoTime()
      val s = c.get[EagerSingleton]

      s.initializedTime should be > start
      s.initializedTime should be < current
    }

    "found cyclic dependencies" in {

      trait HasCycle {
        val obj = inject[A]
      }

      val c = new Helix().newContext

      warn(s"Running cyclic dependency test: A->B->A")
      intercept[HelixException] {
        c.weave[HasCycle]
      }
    }

    "Find a context in parameter" in {
      val h = new Helix
      h.bind[Printer].to[ConsolePrinter]
      h.bind[ConsoleConfig].toInstance(ConsoleConfig(System.err))
      val c = h.newContext
      new ClassWithContext(c)
    }

    "support injection listener" in {
      val h = new Helix
      h.bind[EagerSingleton].asEagerSingleton
      h.bind[ConsoleConfig].toInstance(ConsoleConfig(System.err))

      val counter = new AtomicInteger(0)
      h.addListner(new ContextListener {
        override def afterInjection(t: ObjectType, injectee: Any): Unit = {
          counter.incrementAndGet()
        }
      })
      val c = h.newContext
      c.get[ConsoleConfig]
      counter.get shouldBe 2
    }

    "support factory injection" in {
      val h = new Helix
      h.bind[HelloConfig].toInstance(HelloConfig("Hello Helix!"))
      val c = h.newContext
      val f = new FactoryExample(c)
      f.hello shouldBe "Hello Helix!"
      f.helloFromProvider shouldBe "Hello Helix!"

      info(f.hello2)

    }

    "have scope" in {
      pending
    }


  }
}
