import scala.reflect.runtime.{universe => ru}
import scala.collection.immutable

sealed trait Tree
case class Object(name: String, fields: immutable.ListMap[String, Tree]) extends Tree
// Maps with non-string keys will be coerced
case class Mapping(map: Map[String, Tree]) extends Tree
case class Sequence(seq: List[Tree]) extends Tree
case class Leaf(value: Any) extends Tree

object Tree {
  // If 'x' belongs to class Foo, call: Tree.build(x, scala.reflect.runtime.universe.typeOf[Foo])
  def build(obj: Any, obj_type: ru.Type): Tree = {
    obj match {
      case ls: List[Any] => Sequence(ls.map(
        // Thanks http://stackoverflow.com/questions/12842729
        x => build(x, obj_type.asInstanceOf[ru.TypeRefApi].args.head)
      ).toList)
      case ls: Array[_] => Sequence(ls.map(
        x => build(x, obj_type.asInstanceOf[ru.TypeRefApi].args.head)
      ).toList)
      case m: Map[_, _] => Mapping(m.map({
        case (k, v) => k.toString -> build(v, obj_type.asInstanceOf[ru.TypeRefApi].args.last)
      }))
      // case_class_fields breaks on strings, so a special case
      case x: String => Leaf(x)
      case _ => {
        // TODO Ideally, detect case classes from primitives. This seems to work.
        val fields = case_class_fields(obj_type)
        if (fields.isEmpty) {
          Leaf(obj)
        } else {
          Object(obj_type.toString, immutable.ListMap[String, Tree]() ++ (
            fields.keys.map(name => name -> build(get_field(obj, obj_type, name), fields(name)))
          ))
        }
      }
    }
  }

  // Thanks http://stackoverflow.com/questions/16079113
  private def case_class_fields(tpe: ru.Type): immutable.ListMap[String, ru.Type] = {
    val ctor = tpe.declaration(ru.nme.CONSTRUCTOR)
    val default_ctor =
      if (ctor.isMethod)
        ctor.asMethod
      else
        ctor.asTerm.alternatives.map(_.asMethod).find(_.isPrimaryConstructor).get

    return immutable.ListMap[String, ru.Type]() ++ default_ctor.paramss.reduceLeft(_ ++ _).map({
      sym => sym.name.toString -> tpe.member(sym.name).asMethod.returnType
    })
  }

  private def get_field(obj: Any, obj_tpe: ru.Type, field: String): Any = {
    val m = ru.runtimeMirror(obj.getClass.getClassLoader)
    val term = obj_tpe.declaration(ru.newTermName(field)).asTerm
    val im = m.reflect(obj)
    return im.reflectField(term).get
  }
}

object AsciiTreeViewer {
  def view(t: Tree) = show(t, 0, true)

  private def show(t: Tree, level: Int, indent: Boolean) {
    val header =
      if (indent)
        "  " * level
      else
        ""
    t match {
      case Object(name, fields) => {
        println(header + name)
        for ((field, value) <- fields) {
          print(("  " * (level + 1)) + field + " = ")
          show(value, level + 1, false)
        }
      }
      case Sequence(seq) => {
        println(header + "[")
        seq.foreach(x => show(x, level + 1, true))
        println(("  " * level) + "]")
      }
      case Mapping(m) => {
        println(header + "{")
        for ((k, v) <- m) {
          print(("  " * (level + 1)) + k + " -> ")
          show(v, level + 1, false)
        }
        println(("  " * level) + "}")
      }
      case Leaf(x) => println(header + x)
    }
  }
}

case class Scenario(agents: List[Agent], modes: Array[String], name: String, id: Long)
case class Agent(num: Int, times: Map[String, Double])

object Test {
  def main(args: Array[String]) {
    val a1 = Agent(1, Map("baseline" -> 2.0, "tolls" -> 5.0))
    val a2 = Agent(2, Map("baseline" -> 3.0, "tolls" -> 2.3))
    val s = Scenario(List(a1, a2), Array("baseline", "tolls"), "trial", 42)
    AsciiTreeViewer.view(Tree.build(s, ru.typeOf[Scenario]))
  }
}