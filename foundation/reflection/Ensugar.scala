package org.scalameta.reflection

import scala.tools.nsc.Global
import scala.language.experimental.macros
import scala.reflect.macros.whitebox.Context
import scala.org.scalameta.reflection.Helpers
import org.scalameta.unreachable

// NOTE: this file undoes the desugarings applied by Scala's parser and typechecker to the extent possible with scala.reflect trees
// for instance, insertions of implicit argument lists are undone, because it's a simple Apply => Tree transformation
// however, we can't undo ClassDef, anonymous new or while-loop desugarings, because scala.reflect lacks the trees to represent those in their original form
// some of the undesugarings can be done automatically, some of them require https://github.com/scalameta/scalahost/blob/master/plugin/typechecker/Analyzer.scala
trait Ensugar {
  self: GlobalToolkit =>

  import global._
  import definitions._
  val currentRun = global.currentRun
  import currentRun.runDefinitions._

  import org.scalameta.invariants.Implication
  private def require[T](x: T): Unit = macro org.scalameta.invariants.Macros.require

  def ensugar[Input <: Tree, Output <: Tree](tree: Input)(implicit ev: EnsugarSignature[Input, Output]): Output = {
    def loop(tree: Tree): Tree = {
      object transformer extends Transformer {
        // NOTE: this is the newly established desugaring protocol
        // if a transformer wants to be friendly to us, they can use this protocol to simplify our lives
        object DesugaringProtocol {
          def unapply(tree: Tree): Option[Tree] = tree.metadata.get("original").map(_.asInstanceOf[Tree])
        }

        // TODO: remember macro expansions here, because the host will need to convert and attach them to expandee's attrs
        // TODO: current approaches to macro expansion metadata is a bit weird
        // because both expandees and expansions are attached with the same piece of metadata
        // we need to debug recursive macro expansions to see how they behave in the current system
        object MacroExpansion {
          def unapply(tree: Tree): Option[Tree] = {
            def postprocess(original: Tree): Tree = {
              // NOTE: this method is partially copy/pasted from Reshape.scala in scalac
              def mkImplicitly(tp: Type) = gen.mkNullaryCall(Predef_implicitly, List(tp)).setType(tp)
              val sym = original.symbol
              original match {
                // this hack is necessary until I fix implicit macros
                // so far tag materialization is implemented by sneaky macros hidden in scala-compiler.jar
                // hence we cannot reify references to them, because noone will be able to see them later
                // when implicit macros are fixed, these sneaky macros will move to corresponding companion objects
                // of, say, ClassTag or TypeTag
                case Apply(TypeApply(_, List(tt)), _) if sym == materializeClassTag            => mkImplicitly(appliedType(ClassTagClass, tt.tpe))
                case Apply(TypeApply(_, List(tt)), List(pre)) if sym == materializeWeakTypeTag => mkImplicitly(typeRef(pre.tpe, WeakTypeTagClass, List(tt.tpe)))
                case Apply(TypeApply(_, List(tt)), List(pre)) if sym == materializeTypeTag     => mkImplicitly(typeRef(pre.tpe, TypeTagClass, List(tt.tpe)))
                case _                                                                         => original
              }
            }
            def strip(tree: Tree): Tree = {
              duplicateAndKeepPositions(tree).removeMetadata("expandeeTree").removeAttachment[analyzer.MacroExpansionAttachment]
            }
            // NOTE: the expandeeTree metadata is attached by scala.meta macro expansion
            // the MacroExpansionAttachment attachment is attached by scala.reflect macro expansion
            (tree.metadata.get("expandeeTree").map(_.asInstanceOf[Tree]), tree.attachments.get[analyzer.MacroExpansionAttachment]) match {
              case (Some(original), _) => Some(strip(postprocess(original)))
              case (None, Some(analyzer.MacroExpansionAttachment(original, _))) => Some(strip(postprocess(original)))
              case _ => None
            }
          }
        }

        // TODO: infer which of the TypeBoundsTree bounds were specified explicitly by the user
        // TODO: in the future, when we'll have moved the validating part of refchecks before the macro expansion phase,
        // there won't be any necessity to support TypeTreeWithDeferredRefCheck trees
        object TypeTreeWithOriginal {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case tree @ TypeTree() if tree.original == null =>
              // NOTE: would be nice to disallow TypeTree(tpe) without originals, but allow TypeTree()
              // because that's what one can write syntactically
              // however we have scala.reflect macros, which can generate TypeTree(tpe) trees
              // so for the sake of compatibility we have to remain conservative
              // TODO: however, you know, let's ban synthetic TypeTrees for now and see where it leads
              require(tree.tpe == null)
              None
            case tree @ TypeTree() if tree.original != null =>
              val original = tree.original match {
                case tree: SingletonTypeTree =>
                  treeCopy.SingletonTypeTree(tree, tree.metadata("originalRef").asInstanceOf[Tree])
                case tree @ CompoundTypeTree(templ) =>
                  // NOTE: this attachment is only going to work past typer
                  // but since we're not yet going to implement whitebox macros, that's not yet a problem
                  require(templ.self == noSelfType)
                  val Some(CompoundTypeTreeOriginalAttachment(parents1, stats1)) = templ.attachments.get[CompoundTypeTreeOriginalAttachment]
                  val templ1 = treeCopy.Template(templ, parents1, noSelfType, stats1).setType(NoType).setSymbol(NoSymbol)
                  treeCopy.CompoundTypeTree(tree, templ1)
                case tree @ TypeBoundsTree(lo, hi) =>
                  val lo1 = if (lo.tpe =:= NothingTpe) EmptyTree else lo
                  val hi1 = if (hi.tpe =:= AnyTpe) EmptyTree else hi
                  treeCopy.TypeBoundsTree(tree, lo1, hi1)
                case tree =>
                  tree
              }
              Some(original.setType(tree.tpe))
            case in @ TypeTreeWithDeferredRefCheck() =>
              // NOTE: I guess, we can do deferred checks here as the converter isn't supposed to run in the middle of typer
              // we will have to revisit this in case we decide to support whitebox macros in Palladium
              unapply(in.check())
            case _ =>
              None
          }
        }

        // TODO: test the situation when tree.symbol is a package object
        object RefTreeWithOriginal {
          def unapply(tree: Tree): Option[Tree] = {
            object OriginalIdent { def unapply(tree: Tree): Option[Ident] = tree.metadata.get("originalIdent").map(_.asInstanceOf[Ident]) }
            object OriginalQual { def unapply(tree: Tree): Option[Tree] = tree.metadata.get("originalQual").map(_.asInstanceOf[Tree]) }
            object OriginalName { def unapply(tree: Tree): Option[Name] = tree.metadata.get("originalName").map(_.asInstanceOf[Name]) }
            object OriginalSelect { def unapply(tree: Tree): Option[(Tree, Name)] = OriginalQual.unapply(tree).flatMap(qual => OriginalName.unapply(tree).flatMap(name => Some((qual, name)))) }
            (tree, tree) match {
              // Ident => This is a very uncommon situation, which happens when we typecheck a self reference
              // unfortunately, this self reference can't have a symbol, because self doesn't have a symbol, so we have to do some encoding
              case (This(_), OriginalIdent(orig)) => Some(orig.copyAttrs(tree).removeMetadata("originalIdent"))
              case (Select(_, _), OriginalIdent(orig)) => Some(orig.copyAttrs(tree).removeMetadata("originalIdent"))
              case (Select(_, _), OriginalSelect(origQual, origName)) => Some(treeCopy.Select(tree, origQual, origName).removeMetadata("originalQual", "originalName"))
              case (SelectFromTypeTree(_, _), OriginalSelect(origQual, origName)) => Some(treeCopy.SelectFromTypeTree(tree, origQual, origName).removeMetadata("originalQual", "originalName"))
              case _ => None
            }
          }
        }

        // TODO: discern `... extends C()` and `... extends C`
        // TODO: recover names and defaults in super constructor invocations
        object TemplateWithOriginal {
          def unapply(tree: Tree): Option[Tree] = (tree, tree.metadata.get("originalParents").map(_.asInstanceOf[List[Tree]])) match {
            case (tree @ Template(_, self, body), Some(parents)) =>
              var (superSymbol, superArgss) = treeInfo.firstConstructor(body) match {
                case EmptyTree => (NoSymbol, parents.headOption.flatMap(firstParent => analyzer.superArgs(firstParent)).getOrElse(Nil))
                case DefDef(_, _, _, _, _, Block(_ :+ treeInfo.Applied(core, _, argss), _)) => (core.symbol, argss)
              }
              if (superArgss == List(Nil)) superArgss = Nil
              val parents1 = parents match {
                case firstParent +: otherParents =>
                  val firstParent1 = superArgss.foldLeft(firstParent)((curr, args) => Apply(firstParent, args).setType(firstParent.tpe).appendScratchpad(superSymbol))
                  firstParent1 +: otherParents
                case Nil =>
                  Nil
              }
              Some(treeCopy.Template(tree, parents1, self, body).removeMetadata("originalParents"))
            case _ =>
              None
          }
        }

        object SuperWithOriginal {
          def unapply(tree: Tree): Option[Tree] = (tree, tree.metadata.get("originalThis").map(_.asInstanceOf[This])) match {
            case (tree @ Super(qual, mix), Some(originalQual)) => Some(treeCopy.Super(tree, originalQual, mix).removeMetadata("originalThis"))
            case _ => None
          }
        }

        object ClassOfWithOriginal {
          def unapply(tree: Tree): Option[Tree] = (tree, tree.metadata.get("originalClassOf").map(_.asInstanceOf[Tree])) match {
            case (tree @ Literal(Constant(tpe: Type)), Some(original)) => Some(original.setType(tree.tpe))
            case _ => None
          }
        }

        private def isInferred(tree: Tree): Boolean = tree match {
          case tt @ TypeTree() => tt.nonEmpty && tt.original == null
          case _ => false
        }

        // TODO: wasEmpty is not really working well here and checking nullness of originals is too optimistic
        // however, the former produces much uglier results, so I'm going for the latter
        object MemberDefWithInferredReturnType {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case tree @ ValDef(_, _, tt @ TypeTree(), _) if isInferred(tt) => Some(copyValDef(tree)(tpt = EmptyTree))
            case tree @ DefDef(_, _, _, _, tt @ TypeTree(), _) if isInferred(tt) => Some(copyDefDef(tree)(tpt = EmptyTree))
            case _ => None
          }
        }

        // TODO: support classfile annotation args (ann.original.argss are untyped at the moment)
        object MemberDefWithAnnotations {
          def unapply(tree: Tree): Option[Tree] = {
            def isSyntheticAnnotation(ann: AnnotationInfo): Boolean = ann.atp.typeSymbol.fullName == "scala.reflect.macros.internal.macroImpl"
            def isDesugaredMods(mdef: MemberDef): Boolean = mdef.mods.annotations.isEmpty && tree.symbol.annotations.filterNot(isSyntheticAnnotation).nonEmpty
            def ensugarMods(mdef: MemberDef): Modifiers = mdef.mods.withAnnotations(mdef.symbol.annotations.flatMap(ensugarAnnotation))
            def ensugarAnnotation(ann: AnnotationInfo): Option[Tree] = ann.original match {
              case original if original.nonEmpty => Some(original)
              case EmptyTree if isSyntheticAnnotation(ann) => None
              case EmptyTree => unreachable
            }
            tree match {
              // case tree @ PackageDef(_, _) => // package defs don't have annotations
              case tree: TypeDef if isDesugaredMods(tree) => Some(copyTypeDef(tree)(mods = ensugarMods(tree)))
              case tree: ClassDef if isDesugaredMods(tree) => Some(copyClassDef(tree)(mods = ensugarMods(tree)))
              case tree: ModuleDef if isDesugaredMods(tree) => Some(copyModuleDef(tree)(mods = ensugarMods(tree)))
              case tree: ValDef if isDesugaredMods(tree) => Some(copyValDef(tree)(mods = ensugarMods(tree)))
              case tree: DefDef if isDesugaredMods(tree) => Some(copyDefDef(tree)(mods = ensugarMods(tree)))
              case _ => None
            }
          }
        }

        object MacroDef {
          def unapply(tree: Tree): Option[Tree] = {
            tree match {
              case tree: DefDef if !tree.hasMetadata("originalMacro") =>
                def macroSigs(tree: Tree) = tree match {
                  case tree: DefDef => tree.symbol.annotations.filter(_.tree.tpe.typeSymbol.fullName == "scala.reflect.macros.internal.macroImpl")
                  case _ => Nil
                }
                def parseMacroSig(sig: AnnotationInfo) = {
                  val q"new $_[..$_]($_(..$args)[..$targs])" = sig.tree
                  val metadata = args.collect{
                    case Assign(Literal(Constant(s: String)), Literal(Constant(v))) => s -> v
                    case Assign(Literal(Constant(s: String)), tree) => s -> loop(tree)
                  }.toMap
                  metadata + ("targs" -> targs.map(loop))
                }
                val originalBody = macroSigs(tree) match {
                  case legacySig :: palladiumSig :: Nil =>
                    Some(parseMacroSig(palladiumSig)("implDdef").asInstanceOf[DefDef].rhs)
                  case legacySig :: Nil =>
                    // TODO: obtain the impl ref exactly how it was written by the programmer
                    val legacy = parseMacroSig(legacySig)
                    val className = legacy("className").asInstanceOf[String]
                    val methodName = legacy("methodName").asInstanceOf[String]
                    val isBundle = legacy("isBundle").asInstanceOf[Boolean]
                    val targs = legacy("targs").asInstanceOf[List[Tree]]
                    require(className.endsWith("$") ==> !isBundle)
                    val containerSym = if (isBundle) rootMirror.staticClass(className) else rootMirror.staticModule(className.stripSuffix("$"))
                    val container = Ident(containerSym).setType(if (isBundle) containerSym.asType.toType else containerSym.info)
                    val methodSym = containerSym.info.member(TermName(methodName))
                    var implRef: Tree = Select(container, methodSym).setType(methodSym.info)
                    if (targs.nonEmpty) implRef = TypeApply(implRef, targs).setType(appliedType(methodSym.info, targs.map(_.tpe)))
                    Some(implRef)
                  case _ :: _ =>
                    unreachable
                  case _ =>
                    None
                }
                originalBody.map(originalBody => copyDefDef(tree)(rhs = originalBody).appendMetadata("originalMacro" -> tree))
              case _ => None
            }
          }
        }

        // TODO: test how this works with new
        object TypeApplicationWithInferredTypeArguments {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case TypeApply(fn, targs) if targs.exists(isInferred) => Some(fn)
            case _ => None
          }
        }

        // TODO: infer whether implicit arguments were provided explicitly and don't remove them if so
        // TODO: test how this works with new
        object ApplicationWithInferredImplicitArguments {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case Apply(fn, args) if isImplicitMethodType(fn.tpe) => Some(fn)
            case _ => None
          }
        }

        // TODO: figure out whether the programmer actually wrote `foo(...)` or it was `foo.apply(...)`
        object ApplicationWithInsertedApply {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case Select(qual, _) if tree.symbol.name == nme.apply => Some(qual)
            case _ => None
          }
        }

        object StandalonePartialFunction {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case Typed(Block((gcdef @ ClassDef(_, tpnme.ANON_FUN_NAME, _, _)) :: Nil, q"new ${Ident(tpnme.ANON_FUN_NAME)}()"), tpt)
            if tpt.tpe.typeSymbol == definitions.PartialFunctionClass =>
              val (m, cases) :: Nil = gcdef.impl.body.collect { case DefDef(_, nme.applyOrElse, _, _, _, m @ Match(_, cases :+ _)) => (m, cases) }
              Some(treeCopy.Match(m, EmptyTree, cases))
            case _ =>
              None
          }
        }

        object LambdaPartialFunction {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case Function(x0def :: Nil, Match(x0ref @ Ident(_), cases))
            if x0def.symbol == x0ref.symbol && x0def.name.toString.startsWith("x0$") =>
              Some(Match(EmptyTree, cases).setType(tree.tpe))
            case _ =>
              None
          }
        }

        object CaseClassExtractor {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case tree @ Apply(tpt @ TypeTree(), args) if tpt.tpe.isInstanceOf[MethodType] =>
              // TypeTree[1]().setOriginal(Select[2](Ident[3](scala#26), scala.Tuple2#1688))
              // [1] MethodType(List(TermName("_1")#30490, TermName("_2")#30491), TypeRef(ThisType(scala#27), scala.Tuple2#1687, List(TypeRef(SingleType(SingleType(NoPrefix, TermName("c")#15795), TermName("universe")#15857), TypeName("TermSymbol")#9456, List()), TypeRef(SingleType(SingleType(NoPrefix, TermName("c")#15795), TermName("universe")#15857), TypeName("Ident")#10233, List()))))
              // [2] SingleType(SingleType(ThisType(<root>#2), scala#26), scala.Tuple2#1688)
              // [3] SingleType(ThisType(<root>#2), scala#26)
              require(tpt.original != null && tpt.original.symbol.isModule)
              Some(treeCopy.Apply(tree, tpt.original, args).appendScratchpad(tpt.tpe))
            case _ =>
              None
          }
        }

        // TODO: figure out whether the classtag-style extractor was written explicitly by the programmer
        object ClassTagExtractor {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case outerPat @ UnApply(q"$ref.$unapply[..$targs](..$_)", (innerPat @ Typed(Ident(nme.WILDCARD), _)) :: Nil)
            if outerPat.fun.symbol.owner == definitions.ClassTagClass && unapply == nme.unapply =>
              Some(innerPat)
            case _ =>
              None
          }
        }

        // TODO: test case class unapplication with targs
        // TODO: also test case objects
        // TODO: figure out whether targs were explicitly specified or not
        object VanillaExtractor {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case UnApply(fn @ q"$_.$unapply[..$_](..$_)", args) =>
              require(unapply == nme.unapply || unapply == nme.unapplySeq)
              Some(Apply(treeInfo.dissectApplied(fn).callee, args).setType(tree.tpe))
            case _ =>
              None
          }
        }

        override def transform(tree: Tree): Tree = {
          def logFailure() = {
            def summary(x: Any) = x match { case x: Product => x.productPrefix; case null => "null"; case _ => x.getClass }
            var details = tree.toString.replace("\n", "")
            if (details.length > 60) details = details.take(60) + "..."
            println("(" + summary(tree) + ") " + details)
          }
          try {
            object Desugared {
              def unapply(tree: Tree): Option[Tree] = tree match {
                case DesugaringProtocol(original) => Some(original)
                case MacroExpansion(expandee) => Some(expandee)
                case TypeTreeWithOriginal(original) => Some(original)
                case RefTreeWithOriginal(original) => Some(original)
                case TemplateWithOriginal(original) => Some(original)
                case SuperWithOriginal(original) => Some(original)
                case ClassOfWithOriginal(original) => Some(original)
                case MemberDefWithInferredReturnType(original) => Some(original)
                case MemberDefWithAnnotations(original) => Some(original)
                case MacroDef(original) => Some(original)
                case TypeApplicationWithInferredTypeArguments(original) => Some(original)
                case ApplicationWithInferredImplicitArguments(original) => Some(original)
                case ApplicationWithInsertedApply(original) => Some(original)
                case StandalonePartialFunction(original) => Some(original)
                case LambdaPartialFunction(original) => Some(original)
                case CaseClassExtractor(original) => Some(original)
                case ClassTagExtractor(original) => Some(original)
                case VanillaExtractor(original) => Some(original)
                case _ => None
              }
            }
            tree match {
              case Desugared(original) => transform(original.appendScratchpad(tree))
              case _ => super.transform(tree)
            }
          } catch {
            case err: _root_.java.lang.AssertionError => logFailure(); throw err
            case err: _root_.org.scalameta.UnreachableError.type => logFailure(); throw err
            case ex: _root_.scala.Exception => logFailure(); throw ex
          }
        }
      }
      transformer.transform(tree)
    }
    loop(tree).asInstanceOf[Output]
  }
}

trait EnsugarSignature[Input, Output]
object EnsugarSignature {
  // NOTE: can't be bothered with contravariant implicits, so writing a macro instead
  implicit def materialize[Input, Output]: EnsugarSignature[Input, Output] = macro impl[Input]
  def impl[Input: c.WeakTypeTag](c: Context) = {
    import c.universe._
    import c.internal._
    val input = c.weakTypeOf[Input]
    val output = input match {
      case TypeRef(pre, sym, args) => typeRef(pre, {
        val explicitMapping = Map(
          "TermTree" -> "Tree", // macro expansion
          "SymTree!" -> "Tree", // macro expansion
          "NameTree!" -> "Tree", // macro expansion
          "RefTree!" -> "Tree", // macro expansion
          "Select" -> "Tree", // macro expansion
          "Ident" -> "Tree", // macro expansion
          "UnApply" -> "GenericApply", // extractor-based unapplication
          "AssignOrNamedArg" -> "AssignOrNamedArg", // macros can't expand into these
          "Super" -> "Super", // macros can't expand into these
          "TypeTree" -> "Tree" // not all originals are TypTrees, some are just Trees
                               // the rest of the tree types are mapped to themselves (see below)
        )
        implicit class StringlyTyped(x1: String) {
          def toType: Type = typeRef(pre, pre.member(TypeName(x1.stripSuffix("!"))), Nil)
          def toSymbol: Symbol = pre.member(TypeName(x1.stripSuffix("!")))
        }
        val matches = explicitMapping.keys.filter(k => if (k.endsWith("!")) input =:= k.toType else input <:< k.toType).toList
        val disambiguated = matches.filter(m1 => matches.forall(m2 => !(m1 != m2 && (m2.toType <:< m1.toType))))
        disambiguated match {
          case m :: Nil => explicitMapping(m).toSymbol
          case Nil => sym
          case other => c.abort(c.enclosingPosition, s"$input => ?: derivation failed, because of unexpected match list $other")
        }
      }, args)
      case tpe => tpe
    }
    q"new _root_.org.scalameta.reflection.EnsugarSignature[$input, $output] {}"
  }
}