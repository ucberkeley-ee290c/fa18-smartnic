package interconnect

import chisel3._
import chisel3.util.{Decoupled, log2Ceil}

case class BusParams(maxBeats: Int, maxInFlight: Int, dataWidth: Int) {
  require(maxBeats >= 1)
  val beatBits = log2Ceil(maxBeats - 1)
  require(maxInFlight >= 0)
  val idBits = log2Ceil(maxInFlight)
  require(dataWidth > 0)
  require(dataWidth % 8 == 0)
}

// From the block device IO (master) to the compression block (slave) (64 beats per req guaranteed)
// Also from the MMU/remapper (master) to the block device model (slave) (address on 512B, 64 beats required)
class BlockDeviceIOBusParams extends BusParams(64, 1, 64)

// Used internally to connect (compression -> parity/ECC -> encryption -> mapping/MMU unit)
class CREECBusParams extends BusParams(128, 1, 64)

// TODO: traits can't take parameters in Scala
trait BusAddress {
  // Sector (512B) address (2TB addressable)
  val addr = UInt(32.W)
}

/**
  * This CREECMetadata struct will be written in the sector mapping table
  */
trait CREECMetadata {
  // Indicate whether compression, encryption, ECC was applied to this transaction
  val compressed = Bool()
  val encrypted = Bool()
  val ecc = Bool()
}

class WriteRequest(val p: BusParams) extends Bundle {
  val len = UInt(p.beatBits.W)
  val id = UInt(p.maxInFlight.W)
}

class WriteData(val p: BusParams) extends Bundle {
  val id = UInt(p.maxInFlight.W)
  val data = UInt(p.dataWidth.W)
}

class ReadRequest(val p: BusParams) extends Bundle {
  val len = UInt(p.beatBits.W)
  val id = UInt(p.maxInFlight.W)
}

class ReadData(val p: BusParams) extends Bundle {
  val id = UInt(p.maxInFlight.W)
  val data = UInt(p.dataWidth.W)
}

//TODO: shouldn't wrReq and wrData be Flipped, and rdReq and rdData not?
class CREECBus(val p: BusParams) extends Bundle {
  val wrReq = Decoupled(new WriteRequest(p) with BusAddress with CREECMetadata)
  val wrData = Decoupled(new WriteData(p))
  val rdReq = Decoupled(new ReadRequest(p) with BusAddress)
  val rdData = Flipped(Decoupled(new ReadData(p) with CREECMetadata))
}