package dotty.tools.dotc
package transform

import core._
import DenotTransformers.SymTransformer
import Contexts.Context
import SymDenotations.SymDenotation
import Types._
import Symbols._
import SymUtils._
import Constants._
import TreeTransforms._
import Flags._
import Decorators._

/** Performs the following rewritings for fields of a class:
 *
 *    <mods> val x: T = e
 *      -->  <mods> <stable> def x: T = e
 *    <mods> var x: T = e
 *      -->  <mods> def x: T = e
 *
 *    <mods> val x: T
 *      -->  <mods> <stable> def x: T
 *
 *    <mods> var x: T
 *      -->  <mods> def x: T
 *
 *  Omitted from the rewritings are
 *
 *   - private[this] fields in non-trait classes
 *   - fields generated for static modules (TODO: needed?)
 *   - parameters, static fields, and fields coming from Java
 *
 *  Furthermore, assignements to mutable vars are replaced by setter calls
 *
 *     p.x = e
 *      -->  p.x_=(e)
 *
 *  No fields are generated yet. This is done later in phase Memoize.
 */
class Getters extends MiniPhaseTransform with SymTransformer { thisTransform =>
  import ast.tpd._

  override def phaseName = "getters"

  override def transformSym(d: SymDenotation)(implicit ctx: Context): SymDenotation = {
    def noGetterNeeded =
      d.is(NoGetterNeeded) ||
      d.initial.asInstanceOf[SymDenotation].is(PrivateLocal) && !d.owner.is(Trait) ||
      d.is(Module) && d.isStatic ||
      d.isSelfSym
    if (d.isTerm && d.owner.isClass && d.info.isValueType && !noGetterNeeded) {
      val maybeStable = if (d.isStable) Stable else EmptyFlags
      d.copySymDenotation(
        initFlags = d.flags | maybeStable | AccessorCreationFlags,
        info = ExprType(d.info))
    }
    else d
  }
  private val NoGetterNeeded = Method | Param | JavaDefined | JavaStatic

  override def transformValDef(tree: ValDef)(implicit ctx: Context, info: TransformerInfo): Tree =
    if (tree.symbol is Method) DefDef(tree.symbol.asTerm, tree.rhs) else tree

  override def transformAssign(tree: Assign)(implicit ctx: Context, info: TransformerInfo): Tree =
    if (tree.lhs.symbol is Method) tree.lhs.becomes(tree.rhs) else tree
}
