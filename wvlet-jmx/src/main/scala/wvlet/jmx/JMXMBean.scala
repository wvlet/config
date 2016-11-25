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
package wvlet.jmx

import java.lang.annotation.Annotation
import java.lang.reflect.Method
import javax.management._

import wvlet.obj._
import JMXMBean._

/**
  * Expose object information using DynamicMBean
  */
case class JMXMBean(obj: AnyRef, mBeanInfo: MBeanInfo, attributes: Seq[MBeanParameter]) extends DynamicMBean {
  assert(obj != null)
  private lazy val attributeTable = attributes.map(a => a.name -> a).toMap

  override def getAttributes(attributes: Array[String]): AttributeList = {
    val l = new AttributeList(attributes.length)
    for (a <- attributes) {
      l.add(getAttribute(a))
    }
    l
  }
  override def getAttribute(attribute: String): AnyRef = {
    attributeTable.get(attribute) match {
      case Some(a) => a.get(obj)
      case None =>
        throw new AttributeNotFoundException(s"${attribute} is not found in ${obj.getClass.getName}")
    }
  }
  override def getMBeanInfo: MBeanInfo = mBeanInfo

  override def setAttributes(attributes: AttributeList): AttributeList = {
    val l = new AttributeList(attributes.size())
    import scala.collection.JavaConversions._
    for (a <- attributes.asList().toSeq) {
      l.add(setAttribute(a))
    }
    l
  }
  override def setAttribute(attribute: Attribute): Unit = {
    throw new AttributeNotFoundException(s"Setter for ${attribute.getName} is not found in ${obj.getClass.getName}")
//    attributeTable.get(attribute.getName) match {
//      case Some(a) => a.set(obj, attribute.getValue)
//      case None =>
//
//    }
  }
  override def invoke(actionName: String, params: Array[AnyRef], signature: Array[String]): AnyRef = {
    throw new UnsupportedOperationException(s"JMXMBean.invoke is not supported")
  }
}

object JMXMBean {

  sealed trait MBeanParameter {
    def name : String
    def description : String
    def get(obj:AnyRef) : AnyRef
    def valueType : ObjectType
  }

  case class MBeanObjectParameter(name:String, description:String, param:ObjectParameter) extends MBeanParameter  {
    def valueType = param.valueType
    override def get(obj: AnyRef): AnyRef = {
      param.get(obj).asInstanceOf[AnyRef]
    }
  }

  case class NestedMBeanParameter(name:String, description:String, parentParam:ObjectParameter, nestedParam:ObjectParameter) extends MBeanParameter {
    def valueType = nestedParam.valueType
    override def get(obj: AnyRef): AnyRef = {
      nestedParam.get(parentParam.get(obj)).asInstanceOf[AnyRef]
    }
  }

  private def isNestedMBean(vt:ObjectType) : Boolean = {
    val schema = ObjectSchema(vt.rawType)
    schema.parameters.find(_.findAnnotationOf[JMX].isDefined).isDefined ||
      schema.methods.find(_.findAnnotationOf[JMX].isDefined).isDefined
  }


  private def collectMBeanParameters(parent:Option[ObjectParameter], cl:Class[_]) : Seq[MBeanParameter] = {
    val schema = ObjectSchema(cl)

    val jmxParams : Seq[ObjectParameter] = (schema.parameters ++ schema.methods).filter(_.findAnnotationOf[JMX].isDefined)
    jmxParams
    .flatMap{ p =>
      val description = p.findAnnotationOf[JMX].map(_.description()).getOrElse("")
      val paramName = parent.map(x => s"${x.name}.${p.name}").getOrElse(p.name)
      if(isNestedMBean(p.valueType)) {
        collectMBeanParameters(Some(p), p.valueType.rawType)
      }
      else {
        Seq(
          parent match {
            case Some(pt) =>
              NestedMBeanParameter(paramName, description, pt, p)
            case None =>
              MBeanObjectParameter(paramName, description, p)
          }
        )
      }
    }
  }

  private case class JMXMethod(m: ObjectMethod, jmxAnnotation: JMX)

  def of[A](obj: A): JMXMBean = {
    val cl = obj.getClass
    val description = cl.getAnnotation(classOf[JMX]) match {
      case a if a != null => a.description()
      case _ => ""
    }

    // Collect JMX parameters from the class
    val mbeanParams = collectMBeanParameters(None, cl)
    val attrInfo = mbeanParams.map { x =>
      val desc = new ImmutableDescriptor()
      new MBeanAttributeInfo(
        x.name,
        x.valueType.rawType.getName,
        x.description,
        true,
        false,
        false
      )
    }

    val mbeanInfo = new MBeanInfo(
      cl.getName,
      description,
      attrInfo.toArray[MBeanAttributeInfo],
      Array.empty[MBeanConstructorInfo],
      Array.empty[MBeanOperationInfo],
      Array.empty[MBeanNotificationInfo]
    )

    new JMXMBean(obj.asInstanceOf[AnyRef], mbeanInfo, mbeanParams)
  }

  def collectUniqueAnnotations(m: Method): Seq[Annotation] = {
    collectUniqueAnnotations(m.getAnnotations)
  }

  def collectUniqueAnnotations(lst: Array[Annotation]): Seq[Annotation] = {
    var seen = Set.empty[Annotation]
    val result = Seq.newBuilder[Annotation]

    def loop(lst: Array[Annotation]) {
      for (a <- lst) {
        if (!seen.contains(a)) {
          seen += a
          result += a
          loop(a.annotationType().getAnnotations)
        }
      }
    }
    loop(lst)
    result.result()
  }

//  def buildDescription(a:Annotation) {
//    for(m <- a.annotationType().getMethods.toSeq) {
//      m.getAnnotation(classOf[DescriptorKey]) match {
//        case descriptorKey if descriptorKey != null =>
//          val name = descriptorKey.value()
//          Try(m.invoke(a)).map { v =>
//
//          }
//
//      }
//
//    }
//  }
//

}

