package dev.halone.glasses.drivers.viture

import dev.halone.glasses.api.DriverMatch
import dev.halone.glasses.api.GlassesDriver
import dev.halone.glasses.api.GlassesDriverContract
import dev.halone.glasses.api.UsbIdentity
import dev.halone.glasses.transport.HidTransport
import dev.halone.glasses.transport.HidTransportProvider
import dev.halone.glasses.transport.ReplayHidTransport

class VitureDriverContractTest : GlassesDriverContract() {
    override fun driver(): GlassesDriver = VitureDriver()
    override fun knownIdentity() = UsbIdentity(0x35ca, 0x1121)
    override fun transportsFor(match: DriverMatch) = object : HidTransportProvider {
        override fun open(interfaceId: Int): Result<HidTransport> = Result.success(ReplayHidTransport())
    }
}
