package app.haanf.heartrate.ble

import java.util.UUID

/** Readable labels for the diagnostic view. These are hints only; logic must never rely on them. */
object GattNames {
    val HEART_RATE_SERVICE: UUID = shortUuid(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = shortUuid(0x2A37)
    val CLIENT_CONFIG_DESCRIPTOR: UUID = shortUuid(0x2902)

    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    private val serviceNames = mapOf(
        0x1800 to "Generic Access",
        0x1801 to "Generic Attribute",
        0x180A to "Device Information",
        0x180D to "Heart Rate",
        0x180F to "Battery",
        0x1814 to "Running Speed and Cadence",
        0x1816 to "Cycling Speed and Cadence",
        0x1818 to "Cycling Power",
        0x1826 to "Fitness Machine",
        0xFD6F to "Exposure Notification",
        0xFE2C to "Google Fast Pair",
        0xFEE0 to "Huami (Amazfit)",
        0xFEE1 to "Huami (Amazfit)",
    )

    private val manufacturerNames = mapOf(
        0x0006 to "Microsoft",
        0x004C to "Apple",
        0x0075 to "Samsung",
        0x00E0 to "Google",
        0x0157 to "Huami (Amazfit)",
    )

    fun shortUuid(value: Int): UUID =
        UUID.fromString("0000%04x".format(value) + BASE_SUFFIX)

    /** Short form such as "0x180D Heart Rate", or the full UUID for vendor services. */
    fun describe(uuid: UUID): String {
        val text = uuid.toString()
        if (!text.endsWith(BASE_SUFFIX) || !text.startsWith("0000")) return text
        val short = text.substring(4, 8).toInt(16)
        val hex = "0x%04X".format(short)
        return serviceNames[short]?.let { "$hex $it" } ?: hex
    }

    fun describeManufacturer(id: Int): String {
        val hex = "0x%04X".format(id)
        return manufacturerNames[id]?.let { "$hex $it" } ?: hex
    }
}
