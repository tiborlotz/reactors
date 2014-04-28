package org.reactress






/** A special type of a reactive value that caches the last emitted event.
 *
 *  This last event is called the signal's ''value''.
 *  It can be read using the `Signal`'s `apply` method.
 *  
 *  @tparam T        the type of the events in this signal
 */
trait Signal[@spec(Int, Long, Double) +T]
extends Reactive[T] {
  self =>

  /** Returns the last event produced by `this` signal.
   *
   *  @return         the signal's value
   */
  def apply(): T

  /** Maps the signal using the specified mapping function `f`.
   *
   *  {{{
   *  time ---------------->
   *  this --1---2----3--4->
   *  map  --2---4----6--8->
   *  }}} 
   *
   *  @tparam S       type of the mapped signal
   *  @param f        mapping function for the events in `this` signal
   *  @return         a subscription and a signal with the mapped events
   */
  override def map[@spec(Int, Long, Double) S](f: T => S): Signal[S] with Reactive.Subscription = {
    val sm = new Signal.Map(self, f)
    sm.subscription = self onReaction sm
    sm
  }

  /** A renewed instance of this signal emitting the same events,
   *  but having a different set of subscribers.
   *
   *  {{{
   *  time    ------------->
   *  this    --1----2--3-->
   *  renewed --1----2--3-->
   *  }}}
   *
   *  @return         a subscription and a new instance of `this` signal
   */
  def renewed: Signal[T] with Reactive.Subscription = this.signal(apply())

  /** A signal that only emits events when the value of `this` signal changes.
   *
   *  {{{
   *  time    --------------->
   *  this    --1---2--2--3-->
   *  changes --1---2-----3-->
   *  }}}
   *
   *  @return         a subscription and the signal with changes of `this`
   */
  def changes: Signal[T] with Reactive.Subscription = {
    val initial = this()
    val sc = new Signal.Changes(self, initial)
    sc.subscription = self onReaction sc
    sc
  }

  /** A signal that produces difference events between the current and previous value of `this` signal.
   *
   *  {{{
   *  time ---------------->
   *  this --1--3---6---7-->
   *  diff --z--2---3---1-->
   *  }}}
   *  
   *  @tparam S       the type of the difference event
   *  @param z        the initial value for the difference
   *  @param op       the operator that computes the difference between consecutive events
   *  @return         a subscription and a signal with the difference value
   */
  def diffPast[@spec(Int, Long, Double) S](z: S)(op: (T, T) => S): Signal[S] with Reactive.Subscription = {
    val initial = this()
    val sd = new Signal.DiffPast(self, initial, z, op)
    sd.subscription = self onReaction sd
    sd
  }

  /** Zips values of `this` and `that` signal using the specified function `f`.
   *
   *  Whenever either of the two signals change the resulting signal also changes.
   *  When `this` emits an event, the current value of `that` is used to produce a signal on `that`,
   *  and vice versa.
   *
   *  {{{
   *  time --------------------------------->
   *  this --1----2-----4----------8-------->
   *  that --a----------------b---------c--->
   *  zip  --1,a--2,a---4,a---4,b--8,b--8,c->
   *  }}}
   *
   *  The resulting tuple of events from `this` and `that` is mapped using the
   *  user-specified mapping function `f`.
   *  For example, to produce tuples:
   *
   *  {{{
   *  val tuples = (a zip b) { (a, b) => (a, b) }
   *  }}}
   *
   *  To produce the difference between two integer signals:
   *
   *  {{{
   *  val differences = (a zip b)(_ - _)
   *  }}}
   *
   *  '''Note:''': clients looking into pairing incoming events from two signals
   *  you should use the `sync` method inherited from `Reactive`.
   *
   *  @tparam S        the type of `that` signal
   *  @tparam R        the type of the resulting signal
   *  @param that      the signal to zip `this` with
   *  @param f         the function that maps a tuple of values into an outgoing event
   *  @return          a subscription and the reactive that emits zipped events
   */
  def zip[@spec(Int, Long, Double) S, @spec(Int, Long, Double) R](that: Signal[S])(f: (T, S) => R): Signal[R] with Reactive.Subscription = {
    val sz = new Signal.Zip(self, that, f)
    sz.subscription = Reactive.CompositeSubscription(
      self onReaction sz.selfReactor,
      that onReaction sz.thatReactor
    )
    sz
  }

  /* higher order */

  /** Creates a signal that uses the current signal nested in `this` signal to compute the resulting value,
   *  in effect multiplexing the nested signals.
   *
   *  Whenever the nested signal changes, or the value of the nested signal changes,
   *  an event with the current nested signal value is emitted
   *  and stored as the value of the resulting signal.
   *
   *  Unreacts when both `this` and the last nested signal unreact.
   *
   *  {{{
   *  time      -------------------------------->
   *  this      1--2--3->
   *                     0--0--0-->
   *                               1--2---4--8-->
   *  muxSignal 1--2--3--0--0--0---1--2---4--8-->
   *  }}}
   *  
   *  This is similar to `mux`, but emits the initial value of the signal as an event too --
   *  this is because `mux` does not require the nested reactive to be a signal.
   *
   *  '''Use case:'''
   *
   *  {{{
   *  def muxSignal[S](): Signal[S]
   *  }}}
   *
   *  @tparam S         type of the nested signal
   *  @param evidence   evidence that the type of `this` signal `T` is a signal of type `S`
   *  @return           a subscription and a signal with the multiplexed values.
   */
  def muxSignal[@spec(Int, Long, Double) S]()(implicit evidence: T <:< Signal[S]): Signal[S] with Reactive.Subscription = {
    new Signal.Mux[T, S](this, evidence)
  }

}


object Signal {

  implicit class SignalOps[@spec(Int, Long, Double) T](val self: Signal[T]) {
    def scanPastNow(op: (T, T) => T): Signal[T] with Reactive.Subscription = {
      val initial = self()
      val srp = new Signal.ScanPastNow(self, initial, op)
      srp.subscription = self onReaction srp
      srp
    }

    /** Creates a new signal that emits tuples of the current
     *  and the last event emitted by `this` signal.
     *
     *  {{{
     *  time  ---------------------->
     *  this  1----2------3----4---->
     *  past2 i,1--1,2----2,3--3,4-->
     *  }}}
     *
     *  @param init     the initial previous value, `i` in the diagram above
     *  @return         a subscription and a signal of tuples of the current and last event
     */
    def past2(init: T) = self.scanPast((init, self())) {
      (t, x) => (t._2, x)
    }
  }

  class Map[@spec(Int, Long, Double) T, @spec(Int, Long, Double) S]
    (val self: Signal[T], val f: T => S)
  extends Signal.Default[S] with Reactor[T] with Reactive.ProxySubscription {
    private var cached = f(self.apply)
    def apply() = cached
    def react(value: T) {
      cached = f(value)
      reactAll(cached)
    }
    def unreact() {
      unreactAll()
    }
    var subscription = Reactive.Subscription.empty
  }

  class ScanPastNow[@spec(Int, Long, Double) T]
    (val self: Signal[T], initial: T, op: (T, T) => T)
  extends Signal.Default[T] with Reactor[T] with Reactive.ProxySubscription {
    private var cached = initial
    def apply() = cached
    def react(value: T) {
      cached = op(cached, value)
      reactAll(cached)
    }
    def unreact() {
      unreactAll()
    }
    var subscription = Reactive.Subscription.empty
  }

  class Changes[@spec(Int, Long, Double) T]
    (val self: Signal[T], var cached: T)
  extends Signal.Default[T] with Reactor[T] with Reactive.ProxySubscription {
    def apply() = cached
    def react(value: T) {
      if (cached != value) {
        cached = value
        reactAll(cached)
      }
    }
    def unreact() {
      unreactAll()
    }
    var subscription = Reactive.Subscription.empty
  }

  class DiffPast[@spec(Int, Long, Double) T, @spec(Int, Long, Double) S]
    (val self: Signal[T], var last: T, var cached: S, val op: (T, T) => S)
  extends Signal.Default[S] with Reactor[T] with Reactive.ProxySubscription {
    def apply() = cached
    def react(value: T) {
      cached = op(value, last)
      last = value
      reactAll(cached)
    }
    def unreact() {
      unreactAll()
    }
    var subscription = Reactive.Subscription.empty
  }

  class Zip[@spec(Int, Long, Double) T, @spec(Int, Long, Double) S, @spec(Int, Long, Double) R]
    (val self: Signal[T], val that: Signal[S], val f: (T, S) => R)
  extends Signal.Default[R] with Reactive.ProxySubscription {
    zipped =>
    private[reactress] var cached = f(self(), that())
    private[reactress] var left = 2
    private[reactress] def unreact() {
      left -= 1
      if (left == 0) zipped.unreactAll()
    }
    private[reactress] val selfReactor = new Reactor[T] {
      def react(value: T) {
        cached = f(value, that())
        zipped.reactAll(cached)
      }
      def unreact() = zipped.unreact()
    }
    private[reactress] val thatReactor = new Reactor[S] {
      def react(value: S) {
        cached = f(self(), value)
        zipped.reactAll(cached)
      }
      def unreact() = zipped.unreact()
    }
    def apply() = cached
    var subscription = Reactive.Subscription.empty
  }

  trait Default[@spec(Int, Long, Double) T] extends Signal[T] with Reactive.Default[T]

  class Constant[@spec(Int, Long, Double) T](private val value: T)
  extends Signal[T] with Reactive.Never[T] {
    def apply() = value
  }

  def Constant[@spec(Int, Long, Double) T](value: T) = new Constant(value)

  trait Proxy[@spec(Int, Long, Double) T]
  extends Signal[T] {
    def proxy: Signal[T]
    def apply() = proxy()
    def hasSubscriptions = proxy.hasSubscriptions
    def onReaction(r: Reactor[T]) = proxy.onReaction(r)
  }

  final class Mutable[T <: AnyRef](private val m: T)
  extends Signal.Default[T] with ReactMutable.Subscriptions {
    def apply() = m
    override def onMutated() = reactAll(m)
  }

  def Mutable[T <: AnyRef](v: T) = new Mutable[T](v)

  class Aggregate[@spec(Int, Long, Double) T]
    (private val root: Signal[T] with Reactive.Subscription, private val subscriptions: Seq[Reactive.Subscription])
  extends Signal.Default[T] with Reactive.Subscription {
    def apply() = root()
    def unsubscribe() {
      for (s <- subscriptions) s.unsubscribe()
    }
  }

  def Aggregate[@spec(Int, Long, Double) T](signals: Signal[T]*)(op: (T, T) => T) = {
    require(signals.length > 0)
    val leaves = signals.map(_.renewed)
    var ss = leaves
    while (ss.length != 1) {
      val nextLevel = for (pair <- ss.grouped(2)) yield pair match {
        case Seq(s1, s2) => (s1 zip s2) { (x, y) => op(x, y) }
        case Seq(s) => s
      }
      ss = nextLevel.toBuffer
    }
    val root = ss(0)
    new Aggregate[T](root, leaves)
  }

  class Mux[T, @spec(Int, Long, Double) S]
    (val self: Signal[T], val evidence: T <:< Signal[S])
  extends Signal.Default[S] with Reactor[T] with Reactive.ProxySubscription {
    muxed =>
    import Reactive.Subscription
    private var value: S = _
    private[reactress] var currentSubscription: Subscription = null
    private[reactress] var terminated = false
    def apply() = value
    def newReactor: Reactor[S] = new Reactor[S] {
      def react(v: S) = {
        value = v
        reactAll(value)
      }
      def unreact() {
        currentSubscription = Subscription.empty
        checkUnreact()
      }
    }
    def checkUnreact() = if (terminated && currentSubscription == Subscription.empty) unreactAll()
    def react(v: T) {
      val nextSignal = evidence(v)
      currentSubscription.unsubscribe()
      value = nextSignal()
      currentSubscription = nextSignal onReaction newReactor
      reactAll(value)
    }
    def unreact() {
      terminated = true
      checkUnreact()
    }
    override def unsubscribe() {
      currentSubscription.unsubscribe()
      currentSubscription = Subscription.empty
      super.unsubscribe()
    }
    var subscription: Subscription = null
    def init(e: T <:< Reactive[S]) {
      value = evidence(self()).apply()
      currentSubscription = Subscription.empty
      subscription = self onReaction this
    }
    init(evidence)
  }

}