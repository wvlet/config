/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.inject

import java.util.concurrent.ConcurrentHashMap

import wvlet.inject.Inject.{ClassBinding, InstanceBinding, ProviderBinding, SingletonBinding, _}
import wvlet.inject.InjectionException.{BINDING_NOT_FOUND, CYCLIC_DEPENDENCY}
import wvlet.log.LogSupport
import wvlet.obj.{ObjectSchema, ObjectType}

import scala.reflect.runtime.{universe => ru}
import scala.util.{Failure, Try}

/**
  *
  */
private[inject] class SessionImpl(binding: Seq[Binding], listener: Seq[SessionListener]) extends wvlet.inject.Session with LogSupport {
  self =>

  import scala.collection.JavaConversions._

  private lazy val singletonHolder: collection.mutable.Map[ObjectType, Any] = new ConcurrentHashMap[ObjectType, Any]()

  private[inject] def init() = {
    // Initialize eager singleton
    binding.collect {
      case s@SingletonBinding(from, to, eager) if eager =>
        singletonHolder.getOrElseUpdate(to, buildInstance(to, Set(to)))
      case InstanceBinding(from, obj) =>
        registerInjectee(from, obj)
    }
  }

  def get[A](implicit ev: ru.WeakTypeTag[A]): A = {
    newInstance(ObjectType.of(ev.tpe), Set.empty).asInstanceOf[A]
  }

  def getOrElseUpdate[A](obj: => A)(implicit ev: ru.WeakTypeTag[A]): A = {
    val t = ObjectType.of(ev.tpe)
    val result = binding.find(_.from == t).collect {
      case SingletonBinding(from, to, eager) =>
        singletonHolder.getOrElseUpdate(to, {
          registerInjectee(to, obj)
        })
    }
    result.getOrElse(obj).asInstanceOf[A]
  }

  private def newInstance(t: ObjectType, seen: Set[ObjectType]): AnyRef = {
    trace(s"Search bindings for ${t}")
    if (seen.contains(t)) {
      error(s"Found cyclic dependencies: ${seen}")
      throw new InjectionException(CYCLIC_DEPENDENCY(seen))
    }
    val obj = binding.find(_.from == t).map {
      case ClassBinding(from, to) =>
        newInstance(to, seen + from)
      case InstanceBinding(from, obj) =>
        trace(s"Pre-defined instance is found for ${from}")
        obj
      case SingletonBinding(from, to, eager) =>
        trace(s"Find a singleton for ${to}")
        singletonHolder.getOrElseUpdate(to, buildInstance(to, seen + t + to))
      case b@ProviderBinding(from, provider) =>
        trace(s"Use a provider to generate ${from}: ${b}")
        registerInjectee(from, provider.apply(b.from))
    }
              .getOrElse {
                buildInstance(t, seen + t)
              }
    obj.asInstanceOf[AnyRef]
  }

  private def buildInstance(t: ObjectType, seen: Set[ObjectType]): AnyRef = {
    trace(s"buildInstance: ${t}")
    val schema = ObjectSchema(t.rawType)
    schema.findConstructor match {
      case Some(ctr) =>
        val args = for (p <- schema.constructor.params) yield {
          newInstance(p.valueType, seen)
        }
        trace(s"Build a new instance for ${t}")
        // Add TODO: enable injecting Session to concrete classes
        val obj = schema.constructor.newInstance(args)
        registerInjectee(t, obj)
      case None =>
        if (!(t.rawType.isAnonymousClass || t.rawType.isInterface)) {
          // We cannot inject Session to a class which has no default constructor
          // No binding is found for the concrete class
          throw new InjectionException(BINDING_NOT_FOUND(t))
        }
        // When there is no constructor, generate trait
        import scala.reflect.runtime.currentMirror
        import scala.tools.reflect.ToolBox
        val tb = currentMirror.mkToolBox()
        val typeName = t.rawType.getName.replaceAll("\\$", ".")
        try {
          val code =
            s"""new (wvlet.inject.Session => Any) {
                |  def apply(session:wvlet.inject.Session) = {
                |    new ${typeName} {
                |      protected def __current_session = session
                |    }
                |  }
                |}  """.stripMargin
          trace(s"Compiling a code to embed Session:\n${code}")
          // TODO use Scala macros or cache to make it efficient
          val parsed = tb.parse(code)
          trace(s"Parsed the code: ${parsed}")
          val compiled = tb.compile(parsed)
          trace(s"Compiled the code: ${compiled}")
          val f = compiled().asInstanceOf[Session => Any]
          trace(s"Eval: ${f}")
          val obj = f.apply(this)
          registerInjectee(t, obj)
        }
        catch {
          case e:Throwable =>
            error(s"Failed to inject Session to ${t}")
            //trace(s"Compilation error: ${e.getMessage}", e)
            throw e
        }
    }
  }

  def register[A](obj: A)(implicit ev: ru.WeakTypeTag[A]): A = {
    registerInjectee(ObjectType.ofTypeTag(ev), obj).asInstanceOf[A]
  }

  private def registerInjectee(t: ObjectType, obj: Any): AnyRef = {
    trace(s"Register ${t} (${t.rawType}): ${obj}")
    listener.map(l => Try(l.afterInjection(t, obj))).collect {
      case Failure(e) =>
        error(s"Error in SessionListener", e)
        throw e
    }
    obj.asInstanceOf[AnyRef]
  }

}
