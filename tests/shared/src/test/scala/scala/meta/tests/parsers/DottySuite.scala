package scala.meta.tests
package parsers

import scala.meta._, Type.{Name => TypeName, _}
import scala.meta.dialects.Dotty

class DottySuite extends ParseSuite {
  test("case List(xs: _*)") {
    val tree = pat("List(xs: _*)")
    assert(
      tree.structure == "Pat.Extract(Term.Name(\"List\"), List(Pat.Bind(Pat.Var(Term.Name(\"xs\")), Pat.SeqWildcard())))"
    )
    assert(tree.syntax == "List(xs: _*)")
  }

  test("implicit function type") {
    val Type.ImplicitFunction(List(Type.Name("String")), Type.Name("Int")) =
      tpe("implicit String => Int")

    val Type.ImplicitFunction(List(Type.Name("String"), Type.Name("Boolean")), Type.Name("Int")) =
      tpe("implicit (String, Boolean) => Int")

    val Defn.Def(
      Nil,
      Term.Name("f"),
      Nil,
      List(Nil),
      Some(Type.ImplicitFunction(List(Type.Name("Int")), Type.Name("Int"))),
      _
    ) =
      templStat("def f(): implicit Int => Int = ???")

    val Defn.Val(
      Nil,
      List(Pat.Var(Term.Name("x"))),
      Some(Type.ImplicitFunction(List(Type.Name("String"), Type.Name("Int")), Type.Name("Int"))),
      _
    ) =
      templStat("val x: implicit (String, Int) => Int = ???")

    val Defn.Def(
      Nil,
      Term.Name("f"),
      _,
      Nil,
      Some(
        Type.ImplicitFunction(
          List(Type.Name("A")),
          Type.ImplicitFunction(
            List(Type.Name("B")),
            Type.Tuple(List(Type.Name("A"), Type.Name("B")))
          )
        )
      ),
      _
    ) =
      templStat("def f[A, B]: implicit A => implicit B => (A, B) = ???")
  }

  test("invalid implicit function types") {
    def failWithMessage(code: String) = {
      val error = intercept[ParseException](term(code))
      assert(error.getMessage.contains("function type expected"))
    }

    failWithMessage("{ def f(f: Int => implicit Int): Int = ??? }")
    failWithMessage("{ def f(): implicit Int = ??? }")
    failWithMessage("{ def f(): Int => implicit Int = ??? }")
  }

  test("Type.ImplicitFunction.syntax") {
    assert(t"implicit String => Int".syntax == "implicit String => Int")
    assert(t"implicit String => (Int, Double)".syntax == "implicit String => (Int, Double)")
    assert(t"implicit (String, Double) => Int".syntax == "implicit (String, Double) => Int")
  }

  test("case classes without a parameter list are not allowed") {
    intercept[ParseException](templStat("case class A"))
    intercept[ParseException](templStat("case class A[T]"))
    intercept[ParseException](templStat("case class A[T] private"))
  }

  checkOK("def foo(implicit x: => Int) = 1")
  checkOK("def foo(implicit y: Int, x: => Int) = 1")

  test("trailing commas are allowed") {
    templStat("""|case class A(
                 |  x: X,
                 |)""".stripMargin)
  }

}
