package wvlet.config

import wvlet.surface.Surface

/**
  *
  */
class ObjectBuilder(surface:Surface) {

  val prop = scala.collection.mutable.Map[String, Any]()

  // TODO populate default values

  def set(path:String, v:Any) : this.type = {
    prop += path -> v
    this
  }

  private def constructorArgs : Seq[Any] = {
    val arr = for(p <- surface.params) yield {
      // TODO use the zero value of the type instead of null
      prop.getOrElse(p.name, null)
    }
    arr
  }

  def build: Any = {
     surface.objectFactory match {
       case Some(x) =>
         x.newInstance(constructorArgs)
       case None => new IllegalStateException(s"No constructor is found for ${surface}")
     }
  }
}
