// See README.md for license details.

package ecc

import chisel3._
import chisel3.util._
import interconnect._

// RSEncoders accepts k symbols
// It produces n symbols (k original symbols appended by (n - k) parity symbols)
// Each symbol has a width of *symbolWidth*
class RSEncoder(val rsParams: RSParams = new RSParams()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new DecoupledIO(UInt(rsParams.symbolWidth.W)))
    val out = new DecoupledIO(UInt(rsParams.symbolWidth.W))
  })

  val inReadyReg = RegInit(true.B)
  val outValidReg = RegInit(false.B)

  io.in.ready := inReadyReg
  io.out.valid := outValidReg

  // Counter for input message
  val (inCntVal, inCntDone) = Counter(io.in.fire(), rsParams.k)
  // Counter for output message
  val (outCntVal, outCntDone) = Counter(io.out.fire(), rsParams.n)

  when (inCntDone) {
    // Stop accepting new input when there is enough data
    inReadyReg := false.B
  }
  .elsewhen (io.in.fire()) {
    // Start producing output when the first incoming input is fired
    outValidReg := true.B
  }

  when (outCntDone) {
    // When the last output is fired, ready to accept new input
    outValidReg := false.B
    inReadyReg := true.B
  }

  val Regs = RegInit(VecInit(Seq.fill(rsParams.n - rsParams.k)(
    0.U(rsParams.symbolWidth.W))))
  val inputBitsReg = RegInit(0.U(rsParams.symbolWidth.W))

  when (io.in.fire()) {
    inputBitsReg := io.in.bits
  }
  .otherwise {
    // reset the input register to cater the next input data
    inputBitsReg := 0.U
  }

  // Make sure the arithmetic operations are correct (in Galois field)
  val feedback = Mux(outCntVal < rsParams.k.asUInt(),
                     inputBitsReg ^ Regs(rsParams.n - rsParams.k - 1), 0.U)

  // The encoding computation is done in a LFSR fashion
  Regs.zipWithIndex.foldLeft(0.U) {
    case (prevReg, (nextReg, idx)) => {
      nextReg := prevReg ^ rsParams.GFOp.mul(feedback,
                             rsParams.gCoeffs(idx).asUInt())
      nextReg
    }
  }

  // The first k output messages are the original input messages
  // The subsequent messages are the generated parity messages
  io.out.bits := Mux(outCntVal < rsParams.k.asUInt(),
                     inputBitsReg, Regs(rsParams.n - rsParams.k - 1))
}

// CREECBus integration with the RSEncoder module
// What to do:
// *** Encoder
//   + Receive write header from upstream block (slave)
//   + Send write header to downstream block (master)
//   + Receive write data from upstream block (slave)
//   + Send *encoded* write data to downstream block (master)
class ECCEncoderTop(val rsParams: RSParams,
                    val busInParams: BusParams,
                    val busOutParams: BusParams
  ) extends Module {
  // This is the only bus configuration known to work
  assert(busInParams == BusParams.creec)
  assert(busOutParams == BusParams.ecc)

  val io = IO(new Bundle {
    val slave = Flipped(new CREECBus(busInParams))
    val master = new CREECBus(busOutParams)
  })

  // There are four states
  //  - sRecvHeader: for accepting the header from the slave port
  //  - sSendHeader: for sending the header to the master port
  //  - sRecvData: for accepting the data from the slave port
  //  - sCompute: RS encoding
  //  - sSendData: send the encoded data to the master port
  val sRecvHeader :: sSendHeader :: sRecvData :: sCompute :: sSendData :: Nil = Enum(5)
  val state = RegInit(sRecvHeader)

  val enc = Module(new RSEncoder(rsParams))

  val dataInReg = RegInit(0.U(busInParams.dataWidth.W))
  val dataOutReg = RegInit(0.U(busOutParams.dataWidth.W))
  val headerReg = Reg(chiselTypeOf(io.slave.header.bits))
  val idReg = RegInit(0.U)

  io.master.data.bits.id := idReg
  io.master.header.bits <> headerReg

  io.master.header.bits.ecc := true.B
  io.master.header.bits.eccPadBytes := 0.U
  io.master.header.valid := state === sSendHeader

  io.slave.header.ready := state === sRecvHeader

  io.slave.data.ready := state === sRecvData
  io.master.data.valid := state === sSendData
  io.master.data.bits.data := dataOutReg

  // Trigger the encoding process when sCompute
  enc.io.in.valid := state === sCompute
  enc.io.in.bits := dataInReg
  enc.io.out.ready := state === sCompute

  // Various counters for keeping track of progress
  val (encInCntVal, encInCntDone) = Counter(enc.io.in.fire(), rsParams.k)
  val (encOutCntVal, encOutCntDone) = Counter(enc.io.out.fire(), rsParams.n)

  // Cannot use Counter for this because the maximum value is not statically known
  val beatCnt = RegInit(0.U(32.W))
  val numBeats = RegInit(0.U(32.W))

  switch (state) {
    is (sRecvHeader) {
      // Properly reset registers to prepare for the next input
      beatCnt := 0.U
      dataInReg := 0.U
      dataOutReg := 0.U

      when (io.slave.header.fire()) {
        state := sSendHeader
        numBeats := io.slave.header.bits.len + 1.U
        headerReg := io.slave.header.bits
      }
    }

    is (sSendHeader) {
      when (io.master.header.fire()) {
        state := sRecvData
      }
    }

    is (sRecvData) {
      when (io.slave.data.fire()) {
        // start the encoding process once accepting the first input
        state := sCompute
        dataInReg := io.slave.data.bits.data
        beatCnt := beatCnt + 1.U
      }
    }

    is (sCompute) {
      // Slice the dataInReg register into smaller chunks of size
      // *symbolWidth* for the encoding process
      when (enc.io.in.fire()) {
        dataInReg := (dataInReg >> rsParams.symbolWidth)
      }

      when (enc.io.out.fire()) {
        when (encOutCntDone) {
          state := sSendData
        }

        // Note the endianness
        // The first encoding output is the LSB
        // The last encoding output is the MSB
        val shiftAmt = rsParams.n * rsParams.symbolWidth - rsParams.symbolWidth
        dataOutReg := (dataOutReg >> rsParams.symbolWidth) |
          (enc.io.out.bits << shiftAmt)
      }
    }

    is (sSendData) {
      when (io.master.data.fire()) {
        when (beatCnt === numBeats) {
          // If all data beats have been processed, receive the next header
          state := sRecvHeader
        }
        .otherwise {
          // Process the next data beat
          state := sRecvData
        }
      }
    }

  }
}

