/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package laika.parse.text

import cats.data.NonEmptySet
import laika.parse._

/**
  * A parser that matches a literal string.
  *
  * @author Jens Halm
  */
case class Literal (expected: String) extends PrefixedParser[String] {
  
  require(expected.nonEmpty, "string may not be empty")
  
  val startChars: NonEmptySet[Char] = NonEmptySet.one(expected.head)

  private val msgProvider = Message.forContext { context =>
    val toCapture = Math.min(context.remaining, expected.length)
    val found = context.capture(toCapture)
    s"`$expected' expected but `$found` found"
  }
  
  val underlying: Parser[String] = Parser { in =>
    val source = in.input
    val start = in.offset
    var i = 0
    var j = start
    while (expected.lengthCompare(i) > 0 && source.lengthCompare(j) > 0 && expected.charAt(i) == source.charAt(j)) {
      i += 1
      j += 1
    }
    if (expected.lengthCompare(i)==0) Success(expected, in.consume(i))
    else Failure(msgProvider, in, j)
  }

}
