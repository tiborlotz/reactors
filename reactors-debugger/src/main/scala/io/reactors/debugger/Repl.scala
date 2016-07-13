package io.reactors
package debugger






abstract class Repl {
  /** Unique identifier for the REPL's type.
   */
  def tpe: String

  /** Evaluates a command in the REPL and returns the result.
   */
  def eval(cmd: String): Repl.Result
}


object Repl {
  case class Result(status: Int, output: String)
}