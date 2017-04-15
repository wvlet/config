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
package wvlet.config

import java.io.{File, FileInputStream, FileNotFoundException}
import java.util.{Locale, Properties}

import wvlet.config.PropertiesConfig.ConfigKey
import wvlet.config.YamlReader.loadMapOf
import wvlet.log.LogSupport
import wvlet.log.io.IOUtil
import wvlet.surface.Surface

import scala.reflect.ClassTag
import scala.reflect.runtime.{universe => ru}
import scala.language.experimental.macros

case class ConfigHolder(tpe: Surface, value: Any)

case class ConfigPaths(configPaths: Seq[String]) extends LogSupport {
  info(s"Config file paths: [${configPaths.mkString(", ")}]")
}

object Config extends LogSupport {

  trait ParameterNameFormatter
  case object CanonicalNameFormatter extends ParameterNameFormatter {
    def format(name:String) : String = {
      name.toLowerCase(Locale.US).replaceAll("[ _\\.-]", "")
    }
  }
  private def defaultConfigPath = cleanupConfigPaths(
    Seq(
      ".", // current directory
      sys.props.getOrElse("prog.home", "") // program home for wvlet-launcher
    ))

  def apply(env: String, defaultEnv: String = "default", configPaths: Seq[String] = defaultConfigPath): Config = Config(ConfigEnv(env, defaultEnv, configPaths),
    Map.empty[Surface, ConfigHolder])

  def cleanupConfigPaths(paths: Seq[String]) = {
    val b = Seq.newBuilder[String]
    for (p <- paths) {
      if (!p.isEmpty) {
        b += p
      }
    }
    val result = b.result()
    if (result.isEmpty) {
      Seq(".") // current directory
    }
    else {
      result
    }
  }

  def REPORT_UNUSED_PROPERTIES : Properties => Unit = { unused:Properties =>
    warn(s"There are unused properties: ${unused}")
  }
  def REPORT_ERROR_FOR_UNUSED_PROPERTIES: Properties => Unit = { unused: Properties =>
    throw new IllegalArgumentException(s"There are unused properties: ${unused}")
  }
}

case class ConfigEnv(env: String, defaultEnv: String, configPaths: Seq[String]) {
  def withConfigPaths(paths: Seq[String]): ConfigEnv = ConfigEnv(env, defaultEnv, paths)
}

case class ConfigChange(tpe:Surface, key:ConfigKey, default:Any, current:Any) {
  override def toString = s"[${tpe}] ${key} = ${current} (default = ${default})"
}

import Config._

case class Config private[config](env: ConfigEnv, holder: Map[Surface, ConfigHolder]) extends Iterable[ConfigHolder] with LogSupport {

  // Customization
  def withEnv(newEnv: String, defaultEnv: String = "default"): Config = {
    Config(ConfigEnv(newEnv, defaultEnv, env.configPaths), holder)
  }

  def withConfigPaths(paths: Seq[String]): Config = {
    Config(env.withConfigPaths(paths), holder)
  }

  // Accessors to configurations
  def getAll: Seq[ConfigHolder] = holder.values.toSeq
  override def iterator: Iterator[ConfigHolder] = holder.values.iterator

  def getConfigChanges : Seq[ConfigChange] = {
    val b = Seq.newBuilder[ConfigChange]
    for(c <- getAll) {
      val defaultProps = PropertiesConfig.toConfigProperties(c.tpe, getDefaultValueOf(c.tpe))
      val currentProps = PropertiesConfig.toConfigProperties(c.tpe, c.value)

      for((k, props) <- defaultProps.groupBy(_.key); defaultValue <- props; current <- currentProps.filter(x => x.key == k)) {
        b += ConfigChange(c.tpe, k, defaultValue.v, current.v)
      }
    }
    b.result
  }

  private def find[A](tpe: Surface): Option[Any] = {
    holder.get(tpe).map(_.value)
  }

  def of[ConfigType]: ConfigType = macro ConfigMacros.configOf[ConfigType]
  def getOrElse[ConfigType](default: => ConfigType) : ConfigType = macro ConfigMacros.getOrElse[ConfigType]
  def defaultValueOf[ConfigType] : ConfigType = macro ConfigMacros.defaultValueOf[ConfigType]

  def +(h: ConfigHolder): Config = Config(env, this.holder + (h.tpe -> h))
  def ++(other: Config): Config = {
    Config(env, this.holder ++ other.holder)
  }

  def register[ConfigType](config: ConfigType): Config = macro ConfigMacros.register[ConfigType]

  /**
    * Register the default value of the object as configuration
    * @tparam ConfigType
    * @return
    */
  def registerDefault[ConfigType] : Config = macro ConfigMacros.registerDefault[ConfigType]

  def registerFromYaml[ConfigType: ru.TypeTag : ClassTag](yamlFile: String): Config = {
    val tpe = ObjectType.of(implicitly[ru.TypeTag[ConfigType]])
    val config: Option[ConfigType] = loadFromYaml[ConfigType](yamlFile, onMissingFile = {
      throw new FileNotFoundException(s"${yamlFile} is not found in ${env.configPaths.mkString(":")}")
    })
    config match {
      case Some(x) =>
        this + ConfigHolder(tpe, x)
      case None =>
        throw new IllegalArgumentException(s"No configuration for ${tpe} (${env.env} or ${env.defaultEnv}) is found")
    }
  }

  private def loadFromYaml[ConfigType: ru.TypeTag](yamlFile: String, onMissingFile: => Option[ConfigType]): Option[ConfigType] = {
    val tpe = ObjectType.of(implicitly[ru.TypeTag[ConfigType]])
    findConfigFile(yamlFile) match {
      case None =>
        onMissingFile
      case Some(realPath) =>
        val m = loadMapOf[ConfigType](realPath)(ClassTag(tpe.rawType))
        m.get(env.env) match {
          case Some(x) =>
            info(s"Loading ${tpe} from ${realPath}, env:${env.env}")
            Some(x)
          case None =>
            // Load default
            debug(s"Configuration for ${env.env} is not found in ${realPath}. Load ${env.defaultEnv} configuration instead")
            m.get(env.defaultEnv).map{ x =>
              info(s"Loading ${tpe} from ${realPath}, default env:${env.defaultEnv}")
              x
            }
        }
    }
  }

  def registerFromYamlOrElse[ConfigType: ru.TypeTag : ClassTag](yamlFile: String, defaultValue: => ConfigType): Config = {
    val tpe = ObjectType.of(implicitly[ru.TypeTag[ConfigType]])
    val config = loadFromYaml[ConfigType](yamlFile, onMissingFile = Some(defaultValue))
    this + ConfigHolder(tpe, config.get)
  }

  def overrideWithProperties(props: Properties, onUnusedProperties: Properties => Unit = REPORT_UNUSED_PROPERTIES): Config = {
    PropertiesConfig.overrideWithProperties(this, props, onUnusedProperties)
  }

  def overrideWithPropertiesFile(propertiesFile: String, onUnusedProperties: Properties => Unit = REPORT_UNUSED_PROPERTIES): Config = {
    findConfigFile(propertiesFile) match {
      case None =>
        throw new FileNotFoundException(s"Propertiles file ${propertiesFile} is not found")
      case Some(propPath) =>
        val props = IOUtil.withResource(new FileInputStream(propPath)) { in =>
          val p = new Properties()
          p.load(in)
          p
        }
        overrideWithProperties(props, onUnusedProperties)
    }
  }

  private def getDefaultValueOf(tpe:Surface) : Any = {
    // Create the default object of this ConfigType
    ObjectBuilder(tpe.rawType).build
  }

  private def findConfigFile(name: String): Option[String] = {
    env.configPaths
    .map(p => new File(p, name))
    .find(_.exists())
    .map(_.getPath)
  }
}
