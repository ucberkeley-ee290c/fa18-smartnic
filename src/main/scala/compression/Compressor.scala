// See README.md for license details.

package compression

import chisel3._
import chisel3.util._

case class CompressorParams(inputSize: Int = 64) {
  val outputSize = 32 + inputSize + inputSize/6
}

class Compressor(p: CompressorParams = new CompressorParams()) extends Module {
  val io = IO(new Bundle {
    val dataIn = Input(UInt(p.inputSize.W))
    val inputLength = Input(UInt(32.W))//TODO: Not sure what this should be
    val dataOut = Output(UInt(p.outputSize.W))
    val compressedLength = Output(UInt())
  })
  val varintEncoder = Module(new VarintEncoder())

  val sIdle :: sAccepting :: sCompressing :: Nil = Enum(2)
  val state = RegInit(sAccepting)

  switch(state) {
    is(sIdle) {

    }
    is(sAccepting) {

    }
    is(sCompressing) {

    }
  }

}


// TODO create a VarInt (or VarData) Chisel datatype extension
class VarintEncodedByte extends Bundle {
  val data = UInt(7.W)
  val valid = Bool()

  override def cloneType: this.type = new VarintEncodedByte().asInstanceOf[this.type]
}

/*
  maxBytes is the maximum number of bytes supported. The raw width
  is up to 7*maxBytes bits, and the encoded width is up to 8*maxBytes bits.
 */
case class VarintParams(maxBytes: Int = 5) {
  // TODO add all the bitwidth params here
  val bytes: Int = maxBytes
}


class VarintDecoder(p: VarintParams = VarintParams()) extends Module {
  val io = IO(new Bundle {
    // TODO fix the bitwidths
    val in = Input(UInt((p.bytes * 8).W))
    val out = Output(UInt((p.bytes * 7).W))
  })
  val inEncoded: Vec[UInt] = io.in.asTypeOf(Vec(p.bytes, UInt(8.W)))
  val outDecoded: Vec[UInt] = VecInit(inEncoded.map {
    encByte => encByte.asTypeOf(new VarintEncodedByte).data
  })
  io.out := outDecoded.asUInt()
}

/**
  * Varint Encoding
  *            MSB                            LSB
  * Let   in = 0000000 |1010100 |0000000 |1010000
  * Then out = 00000000|10101001|00000001|10100001
  * LSB of each byte of out indicates a valid 7-bit data field (copied from in)
  */
class VarintEncoder(p: VarintParams = VarintParams()) extends Module {
  val io = IO(new Bundle {
    // TODO: the input bitwidth is wrong (should be a round up to multiple of 8)
    val in = Input(UInt((p.bytes*7).W))
    val out = Output(UInt((p.bytes*8).W))
  })
  val inCoded: Vec[UInt] = io.in.asTypeOf(Vec(p.bytes, UInt(7.W)))
  val numBytes: UInt = (Log2(io.in) >> 3.U).asUInt() + 1.U
  val outCoded : Vec[VarintEncodedByte] = VecInit(inCoded.zipWithIndex.map{case (inByte, i) => {
    val v = Wire(new VarintEncodedByte())
    v.data := inByte
    v.valid := i.U < numBytes
    v
  }})
  io.out := outCoded.asUInt()
}