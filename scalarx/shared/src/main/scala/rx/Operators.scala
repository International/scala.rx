package rx

import rx.Util._

import scala.language.experimental.macros
import scala.reflect.macros._
object Operators {


  def duplicate[T: c.WeakTypeTag](c: Context)(node: c.Expr[Var[T]])(ctx: c.Expr[RxCtx]): c.Expr[Var[T]] = {
    import c.universe._
    val inner = if(c.weakTypeOf[T] <:< c.weakTypeOf[Var[_]]) {
      val innerType = c.weakTypeTag[T].tpe.typeArgs.head
      q"Var.duplicate[$innerType]($node.now)($ctx)"
    } else {
      q"$node.now"
    }
    val res = q"Var($inner)"
    c.Expr[Var[T]](c.resetLocalAttrs(res))
  }



  def filtered[In: c.WeakTypeTag, T: c.WeakTypeTag](c: Context)(f: c.Expr[In => Boolean])(ctx: c.Expr[RxCtx]): c.Expr[Rx[T]] = {
    import c.universe._
    val newCtx =  c.fresh[TermName]("rxctx")
    val tPrefix = transform(c)(c.prefix.tree,newCtx,ctx.tree)
    val tTpe = c.weakTypeOf[T]
    def isHigher = c.weakTypeOf[In] <:< c.weakTypeOf[Var[_]]

    val initValue =
      if (isHigher) q"${c.prefix}.macroImpls.map(${c.prefix}.macroImpls.get($tPrefix.node))(Var.duplicate(_)($newCtx))"
      else q"${c.prefix}.macroImpls.get($tPrefix.node)"

    val checkFunc = q"${transform(c)(f.tree,newCtx,ctx.tree)}"

    val res = c.Expr[rx.Rx[T]](q"""
      ${c.prefix}.macroImpls.filterImpl(
        ($newCtx: RxCtx) => $initValue,
        ${c.prefix}.node,
        ($newCtx: RxCtx) => $tPrefix.node
      )(
        ($newCtx: RxCtx) => $checkFunc,
        ($newCtx: RxCtx) => rx.Node.getDownstream($tPrefix.node),
        ($newCtx: RxCtx) => ${encCtx(c)(ctx)},
        ${c.prefix}.macroImpls.get,
        ${c.prefix}.macroImpls.unwrap,
        $ctx
      )
    """)
    c.Expr[Rx[T]](c.resetLocalAttrs(res.tree))
  }

  type Id[T] = T

  def folded[T: c.WeakTypeTag, V: c.WeakTypeTag, Wrap[_]]
            (c: Context)
            (start: c.Expr[V])
            (f: c.Expr[(V,T) => V])
            (ctx: c.Expr[RxCtx])
            (implicit w: c.WeakTypeTag[Wrap[_]]): c.Expr[Rx[T]] = {

    import c.universe._
    val newCtx =  c.fresh[TermName]("rxctx")
    val tPrefix = transform(c)(c.prefix.tree, newCtx, ctx.tree)
    val foldFunc = transform(c)(f.tree, newCtx, ctx.tree)

    val res = c.Expr[Rx[T]](c.resetLocalAttrs(q"""
      ${c.prefix}.macroImpls.foldImpl(
        ($newCtx: RxCtx) => $start,
        ($newCtx: RxCtx) => $tPrefix.node
      )(
        ($newCtx: RxCtx) => $foldFunc,
        ($newCtx: RxCtx) => rx.Node.getDownstream($tPrefix.node),
        ${encCtx(c)(ctx)},
        ${c.prefix}.macroImpls.get,
        ${c.prefix}.macroImpls.unwrap,
        ${c.prefix}.macroImpls.unwrap
      )
    """))
    res
  }

  def mapped[T: c.WeakTypeTag, V: c.WeakTypeTag, Wrap[_]]
            (c: Context)
            (f: c.Expr[T => V])
            (ctx: c.Expr[RxCtx])
            (implicit w: c.WeakTypeTag[Wrap[_]])
            : c.Expr[Rx[V]] = {

    import c.universe._
    val newCtx =  c.fresh[TermName]("rxctx")
    val tryTpe = c.weakTypeOf[scala.util.Try[_]]
    val tPrefix = transform(c)(c.prefix.tree,newCtx,ctx.tree)
    val call =  transform(c)(f.tree,newCtx,ctx.tree)

    val res = c.Expr[Rx[V]](c.resetLocalAttrs(q"""
      ${c.prefix}.macroImpls.mappedImpl(
        ($newCtx: RxCtx) => $tPrefix.node,
        ($newCtx: RxCtx) => $call
      )(
        ($newCtx: RxCtx) => rx.Node.getDownstream($tPrefix.node),
        ${encCtx(c)(ctx)},
        ${c.prefix}.macroImpls.get,
        ${c.prefix}.macroImpls.unwrap
      )
    """
    ))
    res
  }

  def flatMapped[T: c.WeakTypeTag, V: c.WeakTypeTag, Wrap[_]]
                (c: Context)
                (f: c.Expr[Wrap[T] => Wrap[Rx[V]]])
                (ctx: c.Expr[RxCtx])
                (implicit w: c.WeakTypeTag[Wrap[_]])
                : c.Expr[Rx[V]] = {

    import c.universe._
    val newCtx =  c.fresh[TermName]("rxctx")
    val tryTpe = c.weakTypeOf[scala.util.Try[_]]
    val tPrefix = transform(c)(c.prefix.tree,newCtx,ctx.tree)
    val call =  transform(c)(f.tree,newCtx,ctx.tree)

    val res = c.Expr[Rx[V]](c.resetLocalAttrs(q"""
      ${c.prefix}.macroImpls.flatMappedImpl(
        ($newCtx: RxCtx) => $tPrefix.node,
        ($newCtx: RxCtx) => $call
      )(
        ($newCtx: RxCtx) => rx.Node.getDownstream($tPrefix.node),
        ${encCtx(c)(ctx)},
        ${c.prefix}.macroImpls.get,
        ${c.prefix}.macroImpls.unwrap
      )
    """
    ))
    res
  }

  class Operator[Wrap[_]]{
    def flatMappedImpl[T, V](tPrefix: RxCtx => Node[T],
                         call: RxCtx => Wrap[T] => Wrap[Rx[V]])
                        (downStream: RxCtx => Seq[Node[_]],
                         enclosing: RxCtx,
                         toT: Node[T] => Wrap[T],
                         toOut: Wrap[Rx[V]] => Rx[V]): Rx[V] = {

      Rx.build { implicit newCtx: RxCtx =>
        downStream(newCtx).foreach(_.Internal.addDownstream(newCtx))
        toOut(call(newCtx)(toT(tPrefix(newCtx)))).apply()
      }(enclosing)
    }
    def mappedImpl[T, V](tPrefix: RxCtx => Node[T],
                         call: RxCtx => Wrap[T] => Wrap[V])
                        (downStream: RxCtx => Seq[Node[_]],
                         enclosing: RxCtx,
                         toT: Node[T] => Wrap[T],
                         toOut: Wrap[V] => V): Rx[V] = {

      Rx.build { implicit newCtx: RxCtx =>
        downStream(newCtx).foreach(_.Internal.addDownstream(newCtx))
        toOut(call(newCtx)(toT(tPrefix(newCtx))))
      }(enclosing)
    }

    def foldImpl[T, V](start: RxCtx => Wrap[V],
                       tPrefix: RxCtx => Node[T])
                      (f: RxCtx => (Wrap[V], Wrap[T]) => Wrap[V],
                       downStream: RxCtx => Seq[Node[_]],
                       enclosing: RxCtx,
                       toT: Node[T] => Wrap[T],
                       toOut: Wrap[V] => V,
                       toOut2: Wrap[T] => T): Rx[V] = {

      var prev: Wrap[V] = start(enclosing)
      Rx.build { newCtx: RxCtx =>
        downStream(newCtx).foreach(_.Internal.addDownstream(newCtx))
        prev = f(newCtx)(prev, toT(tPrefix(newCtx)))
        toOut(prev)
      }(enclosing)
    }

    /**
      * Split into two to make type-inference work
      */
    def reducedImpl[T](initValue: RxCtx => Wrap[T],
                        tPrefix: Node[T],
                        prefix: Node[T])
                       (reduceFunc: (Wrap[T], Wrap[T]) => Wrap[T],
                        toT: Node[T] => Wrap[T],
                        toOut: Wrap[T] => T,
                        enclosing: RxCtx,
                        downStream: Seq[Node[_]]): Rx[T] = {
      var init = true
      def getPrev = toT(prefix)

      var prev = getPrev

      def next: T = toOut(prev)

      Rx.build { newCtx: RxCtx =>
        downStream.foreach(_.Internal.addDownstream(newCtx))
        if(init) {
          init = false
          prev = initValue(newCtx)
          next
        } else {
          prev = reduceFunc(prev, getPrev)
          next
        }
      }(enclosing)
    }

    def filterImpl[T, Out](start: RxCtx => T,
                           prefix: Node[Out],
                           tPrefix: RxCtx => Node[Out])
                          (f: RxCtx => T => Boolean,
                           downStream: RxCtx => Seq[Node[_]],
                           enclosing: RxCtx => RxCtx,
                           toT: Node[Out] => T,
                           toOut: T => Out,
                           ctx: RxCtx) = {

      var init = true
      var prev = toT(prefix)
      Rx.build { newCtx: RxCtx =>
        downStream(newCtx).foreach(_.Internal.addDownstream(newCtx))
        if(f(newCtx)(toT(tPrefix(newCtx))) || init) {
          init = false
          prev = start(newCtx)
        }
        toOut(prev)
      }(enclosing(ctx))
    }

  }

  def reduced[T: c.WeakTypeTag, Wrap[_]]
             (c: Context)
             (f: c.Expr[(Wrap[T], Wrap[T]) => Wrap[T]])
             (ctx: c.Expr[RxCtx])
             (implicit w: c.WeakTypeTag[Wrap[_]]): c.Expr[Rx[T]] = {
    import c.universe._
    val newCtx =  c.fresh[TermName]("rxctx")
    val tPrefix = transform(c)(c.prefix.tree,newCtx,ctx.tree)
    val isHigher = c.weakTypeOf[T] <:< c.weakTypeOf[Var[_]]

    val reduceFunc = transform(c)(f.tree,newCtx,ctx.tree)

    val initValue =
      if (isHigher) q"${c.prefix}.macroImpls.map(${c.prefix}.macroImpls.get($tPrefix.node))(Var.duplicate(_)($newCtx))"
      else q"${c.prefix}.macroImpls.get($tPrefix.node)"

    val res = c.Expr[Rx[T]](q"""
      ${c.prefix}.macroImpls.reducedImpl[${weakTypeOf[T]}](
        ($newCtx: RxCtx) => $initValue,
        $tPrefix.node,
        ${c.prefix}.node
      )(
        $reduceFunc,
        ${c.prefix}.macroImpls.get,
        ${c.prefix}.macroImpls.unwrap,
        ${encCtx(c)(ctx)},
        rx.Node.getDownstream($tPrefix.node)
      )
    """)

    c.Expr[Rx[T]](c.resetLocalAttrs(res.tree))
  }
}
