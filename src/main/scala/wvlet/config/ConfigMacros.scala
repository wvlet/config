package wvlet.config

import scala.language.experimental.macros
import scala.reflect.macros.{blackbox => sm}
/**
  *
  */
private[config] object ConfigMacros {

  def configOf[ConfigType: c.WeakTypeTag](c: sm.Context): c.Tree = {
    val t = implicitly[c.WeakTypeTag[ConfigType]].tpe
    new Helper[c.type](c)(t).config
  }

  def getOrElse[ConfigType: c.WeakTypeTag](c: sm.Context)(default: c.Tree): c.Tree = {
    val t = implicitly[c.WeakTypeTag[ConfigType]].tpe
    new Helper[c.type](c)(t).getOrElse(default)
  }

  def defaultValueOf[ConfigType: c.WeakTypeTag](c: sm.Context): c.Tree = {
    val t = implicitly[c.WeakTypeTag[ConfigType]].tpe
    new Helper[c.type](c)(t).defaultValue
  }

  def register[ConfigType: c.WeakTypeTag](c: sm.Context)(config: c.Tree): c.Tree = {
    val t = implicitly[c.WeakTypeTag[ConfigType]].tpe
    new Helper[c.type](c)(t).register(config)
  }

  def registerDefault[ConfigType: c.WeakTypeTag](c: sm.Context): c.Tree = {
    val t = implicitly[c.WeakTypeTag[ConfigType]].tpe
    new Helper[c.type](c)(t).registerDefault
  }

  private[surface] class Helper[C <: sm.Context](val c: C)(t:c.Type) {
    import c.universe._

    def surface : Tree = q"wvlet.surface.Surface.of[$t]"

    def config : Tree =
      q"""
       ${c.prefix}.find(${surface}) match {
         case Some(x) => x.asInstanceOf[$t]
         case None => new IllegalArgumentException("No config value for " + "${t.toString}" + " is found")
       }
      """

    def getOrElse(default: Tree): Tree =
      q"""
       ${c.prefix}.find(${surface}) match {
         case Some(x) => x.asInstanceOf[$t]
         case None => ${default}
       }
      """

    def defaultValue = q"${c.prefix}.getDefaultValueOf(${surface}).asInstanceOf[$t]"
    def register(config: Tree) = q"${c.prefix}.this + wvlet.config.ConfigHolder(${surface}, ${config})"
    def registerDefault = q"${c.prefix}.this + wvlet.config.ConfigHolder(${surface}, ${defaultValue})"
  }

}
