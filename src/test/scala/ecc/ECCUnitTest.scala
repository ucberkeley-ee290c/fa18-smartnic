package ecc
import scala.math

import chisel3.iotesters
import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester}

// Software implementation of the RSEncoder
class RSCode(numSyms: Int, symbolWidth: Int,
             msgs: Seq[Int]) {
  val numVals = BigInt(2).pow(symbolWidth).toInt
  var Log2Val: Array[Int] = new Array[Int](numVals)
  var Val2Log: Array[Int] = new Array[Int](numVals)
  // Primitive Polynomial
  val fConst = {
    symbolWidth match {
      // x^3 + x + 1
      case 3 => Integer.parseInt("1011", 2)
      // x^4 + x + 1
      case 4 => Integer.parseInt("10011", 2)
      // x^5 + x^2 + 1
      case 5 => Integer.parseInt("100101", 2)
      // x^6 + x + 1
      case 6 => Integer.parseInt("1000011", 2)
      // x^7 + x^3 + 1
      case 7 => Integer.parseInt("10001001", 2)
      // x^8 + x^4 + x^3 + x^2 + 1
      case 8 => Integer.parseInt("100011101", 2)
      case _ => 0
    }
  }

  require(fConst != 0, "Unsupported symbol width!")

  Log2Val(0) = 1
  Log2Val(1) = 2 // according to the spec, usually choose a^1 to be 2
  for (i <- 2 until numVals) {
    Log2Val(i) = Log2Val(i - 1) << 1
    if (Log2Val(i) >= numVals) {
      Log2Val(i) = (Log2Val(i) % numVals) ^ (fConst % numVals)
    }
  }

  for (i <- 0 until numVals) {
    Val2Log(Log2Val(i)) = i
  }

  def add(a: Int, b: Int): Int = {
    a ^ b
  }

  def mul(a: Int, b: Int): Int = {
    if (a == 0 || b == 0) {
      0
    } else {
      Log2Val((Val2Log(a) + Val2Log(b)) % (numVals - 1))
    }
  }

  def pow(a: Int, n: Int): Int = {
    var t: Int = 1
    for (i <- 0 until n) {
      t = mul(a, t)
    }
    t
  }

  def printValRootTable() {
    for (i <- 0 until numVals) {
      printf("Val2Log(%d) = %d\n", i, Val2Log(i))
    }
  }

  def printLogRootTable() {
    for (i <- 0 until numVals) {
      printf("Log2Val(%d) = %d\n", i, Log2Val(i))
    }
  }

  val numMsgs = msgs.size
  val numPars = numSyms - numMsgs

  // Generator Polynomial
  // g(X) = (X + a^1)(X + a^2)(X + a^3) ... (X + a^numPars)
  //      = gCoeffs(0) + gCoeffs(1) * X^1 + gCoeffs(2) * X^2 + ... + gCoeffs(numPars) * X^numPars
  val gCoeffs = {
    var coeffs = Seq[Int]()
    val powSets = (1 to numPars).toSet[Int].subsets.map(_.toList).toList
    for (j <- numPars to 1 by -1) {
      var tmpSum: Int = 0
      for (i <- 0 until powSets.size) {
        if (powSets(i).size == j) {
          var tmpMul: Int = 1
          for (k <- 0 until j) {
            tmpMul = mul(tmpMul, Log2Val(powSets(i)(k)))
          }
          tmpSum = add(tmpSum, tmpMul)
        }
      }
      coeffs = coeffs :+ tmpSum
    }
    coeffs
  }

  def encode(): Seq[Int] = {
    var pars: Array[Int] = new Array[Int](numPars)
    for (i <- 0 until numMsgs) {
      val tmp = pars.map(x => x)
      val feedback = add(msgs(i), pars(numPars - 1))

      for (j <- 0 until numPars) {
        if (j == 0) {
          pars(j) = mul(feedback, gCoeffs(j))
        } else {
          pars(j) = add(mul(feedback, gCoeffs(j)), tmp(j - 1))
        }
      }
    }
    // Append input messages with the generated parity symbols
    msgs ++ pars.reverse
  }


  // Make sure the generated symbol sequence forms a polynomial
  // that equals to zero at all the *numPars* roots
  // E.g., out(X) = syms(0) * X^(n - 1) + sym(1) * X^(n - 2) + ... + sym(n - 1) * X^0
  // then we need to ensure that: out(a^1) = 0, out(a^2) = 0, ... out(a^(numPars)) = 0
  def verify_encode(syms: Seq[Int]): Boolean = {
    var check: Boolean = true
    for (r <- 1 to numPars) {
      var t: Int = 0
      for (i <- 0 until syms.size) {
        t = add(t, mul(syms(i), pow(Log2Val(r), syms.size - i - 1)))
      }
      if (t != 0) {
        check = false
      }
    }
    check
  }

}

class RSEncoderUnitTester(c: RSEncoder, swSyms: Seq[Int]) extends PeekPokeTester(c) {
  var hwSyms = List[Int]()

  poke(c.io.in.valid, true)
  poke(c.io.out.ready, true)

  for (i <- 0 until c.param.k) {
    poke(c.io.in.bits, swSyms(i))
    // This is rather awkward
    if (peek(c.io.out.valid) == BigInt(1) &&
        peek(c.io.out.ready) == BigInt(1)) {
      hwSyms = hwSyms :+ peek(c.io.out.bits).toInt
    }
    step(1)
  }

  poke(c.io.in.valid, false)

  while (peek(c.io.out.valid) == BigInt(1) &&
         peek(c.io.out.ready) == BigInt(1)) {
    hwSyms = hwSyms :+ peek(c.io.out.bits).toInt
    step(1)
  }

  for (i <- 0 until c.param.n) {
    printf("hwSyms(%d) = %d\n", i, hwSyms(i))
  }

  for (i <- 0 until c.param.n) {
    expect(hwSyms(i) == swSyms(i), "symbols do not match!")
  }
}

/**
  * From within sbt use:
  * testOnly ecc.ECCTester
  * From a terminal shell use:
  * sbt 'testOnly ecc.ECCTester'
  */
class ECCTester extends ChiselFlatSpec {
  // RS(16, 8)
  val numSymbols = 16
  val numMsgs = 8
  val symbolWidth = 8
  var msgs = Seq.fill(numMsgs) {
    scala.util.Random.nextInt(BigInt(2).pow(symbolWidth).toInt - 1)
  }

  val rs = new RSCode(numSymbols, symbolWidth, msgs)

  val gCoeffs = rs.gCoeffs
  val fConst = rs.fConst

  val swSyms = rs.encode()
  for (i <- 0 until swSyms.size) {
    printf("swSyms(%d) = %d\n", i, swSyms(i))
  }

  // Need to pass this test to go further
  require(rs.verify_encode(swSyms), "Incorrect software RSEncoding generator!")

  val params = RSParams(
    n = numSymbols,
    k = msgs.size,
    symbolWidth = symbolWidth,
    gCoeffs = gCoeffs,
    fConst = fConst
  )

  "RSEncoder" should "work" in {
    iotesters.Driver.execute(Array("-tbn", "verilator", "-fiwv"), () =>
    new RSEncoder(params)) {
      c => new RSEncoderUnitTester(c, swSyms)
    } should be(true)
  }
}
