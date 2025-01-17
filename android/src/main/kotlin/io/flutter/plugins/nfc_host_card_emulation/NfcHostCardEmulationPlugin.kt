package io.flutter.plugins.nfc_host_card_emulation

import android.util.Log

import android.nfc.NfcAdapter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.NonNull

import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.FlutterPlugin

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

import android.content.BroadcastReceiver

import android.content.pm.PackageManager

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

import android.media.MediaPlayer
import android.os.Vibrator
import android.os.VibrationEffect

import android.widget.Toast



/** NfcHostCardEmulationPlugin */
class NfcHostCardEmulationPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private lateinit var activity: Activity
    private lateinit var channel: MethodChannel
    private var nfcAdapter: NfcAdapter? = null

    // base methods
    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "nfc_host_card_emulation")
        channel.setMethodCallHandler(this)
        // Get the Context from FlutterPluginBinding
        val context = flutterPluginBinding.applicationContext

        // Initialize nfcAdapter using the Context
        Log.d("NfcHostCardEmulationPlugin", "onAttachedToEngine")
        nfcAdapter = NfcAdapter.getDefaultAdapter(context)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activity.registerReceiver(apduServiceReciever, IntentFilter("apduCommand"))
    }

    override fun onDetachedFromActivity() {
        activity.unregisterReceiver(apduServiceReciever);
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        activity.registerReceiver(apduServiceReciever, IntentFilter("apduCommand"))
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity.unregisterReceiver(apduServiceReciever);
    }

    // nfc host card emulation methods
    private val apduServiceReciever = object : BroadcastReceiver() {
        override fun onReceive(contxt: Context?, intent: Intent?) {
            when (intent?.action) {
                "apduCommand" -> channel.invokeMethod(
                    "apduCommand", mapOf(
                        "port" to intent!!.getIntExtra("port", -1),
                        "command" to intent!!.getByteArrayExtra("command"),
                        "data" to intent!!.getByteArrayExtra("data")
                    )
                )
            }
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "init" -> init(call, result)
            "addApduResponse" -> addApduResponse(call, result)
            "removeApduResponse" -> removeApduResponse(call, result)
            "checkNfc" -> {
                // 1. Check if NFC feature is available on the device
                val packageManager = activity.packageManager
                val hasNfcFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)

                if (!hasNfcFeature) {
                    // 2. If NFC feature is not present, return 'notSupported'
                    result.success(null)  // Directly use the string "notSupported"
                    return
                }

                // 3. If NFC feature is present, check if it's enabled
                val isNfcEnabled = nfcAdapter?.isEnabled ?: false

                if (isNfcEnabled) {
                    result.success(true)  // Directly use the string "enabled"
                } else {
                    result.success(false)  // Directly use the string "disabled"
                }
            }

            else -> result.notImplemented()
        }
    }

    private fun init(call: MethodCall, result: Result) {
        try {
            AndroidHceService.permanentApduResponses =
                call.argument<Boolean>("permanentApduResponses")!!;
            AndroidHceService.listenOnlyConfiguredPorts =
                call.argument<Boolean>("listenOnlyConfiguredPorts")!!;

            val aid = call.argument<ByteArray>("aid");
            if (aid != null) AndroidHceService.aid = aid;

            val cla = call.argument<Int>("cla")?.toByte();
            if (cla != null) AndroidHceService.cla = cla;

            val ins = call.argument<Int>("ins")?.toByte();
            if (ins != null) AndroidHceService.ins = ins;

            val AID = AndroidHceService.byteArrayToString(AndroidHceService.aid)
            Log.d("HCE", "HCE initialized. AID = $AID.")
        } catch (e: Exception) {
            result.error("invalid method parameters", "invalid parameters in 'init' method", null)
        }

        result.success(null)
    }


    private fun addApduResponse(call: MethodCall, result: Result) {
        try {
            val port = call.argument<Int>("port")!!
            val data = call.argument<ByteArray>("data")!!

            AndroidHceService.portData[port] = data

            val portData = AndroidHceService.byteArrayToString(AndroidHceService.portData[port]!!)
            Log.d("HCE", "Added $portData to port $port")
        } catch (e: Exception) {
            result.error(
                "invalid method parameters",
                "invalid parameters in 'addApduResponse' method",
                null
            )
        }

        result.success(null)
    }

    private fun removeApduResponse(call: MethodCall, result: Result) {
        try {
            val port = call.argument<Int>("port")!!
            AndroidHceService.portData.remove(port)

            Log.d("HCE", "Removed APDU response from port $port")
        } catch (e: Exception) {
            result.error(
                "invalid method parameters",
                "invalid parameters in 'removeApduResponse' method",
                null
            )
        }

        result.success(null)
    }
}


class AndroidHceService : HostApduService() {
    companion object {
        var permanentApduResponses = false
        var listenOnlyConfiguredPorts = false
        private lateinit var vibrator: Vibrator
        private lateinit var mediaPlayer: MediaPlayer

        var aid = byteArrayOf(
            0xA0.toByte(),
            0x00.toByte(),
            0xDA.toByte(),
            0xDA.toByte(),
            0xDA.toByte(),
            0xDA.toByte(),
            0xDA.toByte()
        )
        var cla: Byte = 0
        var ins: Byte = 0xA4.toByte()

        var portData = mutableMapOf<Int, ByteArray>()

        public fun byteArrayToString(array: ByteArray): String {
            var str = "["
            for (i in 0 until array.size - 1)
                str += " ${array[i].toUByte().toString(16)},"
            str += " ${array[array.size - 1].toUByte().toString(16)} ]"

            return str
        }
    }

    // APDU response codes
    private val SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte()) // Success
    private val BAD_LENGTH = byteArrayOf(0x67.toByte(), 0x00.toByte()) // Wrong length
    private val UNKNOWN_CLA = byteArrayOf(0x6E.toByte(), 0x00.toByte()) // Class not supported
    private val UNKNOWN_INS = byteArrayOf(0x6D.toByte(), 0x00.toByte()) // Instruction not supported
    private val UNSUPPORTED_CHANNEL =
        byteArrayOf(0x68.toByte(), 0x81.toByte()) // Secure messaging not supported
    private val FAILURE = byteArrayOf(0x6F.toByte(), 0x00.toByte()) // General error

    // APDU Command for selecting the AID
    private val APDU_SELECT = byteArrayOf(
        0x00.toByte(), // CLA   - Class - Class of instruction
        0xA4.toByte(), // INS   - Instruction - Instruction code
        0x04.toByte(), // P1    - Parameter 1 - Instruction parameter 1
        0x00.toByte(), // P2    - Parameter 2 - Instruction parameter 2
        AndroidHceService.aid.size.toByte(), // Lc field  - Number of bytes present in the data field of the command
        *AndroidHceService.aid, // Unpack the AID array into the APDU_SELECT
        0x00.toByte()  // Le field  - Maximum number of bytes expected in the data field of the response to the command
    )

    private val TAG = "HostApduService"

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        Log.d("HCE", "APDU Command ${byteArrayToString(commandApdu)}")
        Log.d("HCE", "0")


        // 1. Handle SELECT command for application selection
        if (commandApdu.copyOfRange(0, 5).contentEquals(APDU_SELECT.copyOfRange(0, 5)) &&
            commandApdu.copyOfRange(5, commandApdu.size).contentEquals(AndroidHceService.aid)
        ) {
            Log.i(TAG, "Application selected successfully")

            // Add vibration
            if (vibrator.hasVibrator()) {
                val vibrationEffect = VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE) // 200ms vibration
                vibrator.vibrate(vibrationEffect)
            }

            Toast.makeText(this, "NFC Card validated!", Toast.LENGTH_LONG).show()

            return SUCCESS
        }
        Log.d("HCE", "1")

        // 2. Basic validation of CLA and INS
        if (commandApdu[0] != cla) return UNKNOWN_CLA
        if (commandApdu[1] != ins) return UNKNOWN_INS
        Log.d("HCE", "2")

        val isSelectAidCommand = (commandApdu[1] == 0xA4.toByte()) && (commandApdu[2] == 0x04.toByte())


        // 3. Extract port number and validate length
        Log.d("HCE", "2,5")
        val port: Int = commandApdu[3].toUByte().toInt()
        Log.d("HCE", commandApdu[4].toUByte().toString())
        Log.d("HCE", AndroidHceService.aid.size.toString())
        if (commandApdu[4].toInt() != AndroidHceService.aid.size) return BAD_LENGTH
        Log.d("HCE", "3")

        // 4. Validate AID (only if it's a SELECT AID command)
        if (isSelectAidCommand) {
            for (i in 0 until AndroidHceService.aid.size)
                if (commandApdu[i + 5] != AndroidHceService.aid[i]) {
                    Log.d("HCE", "Invalid AID")
                    return UNSUPPORTED_CHANNEL
                }
        }

        Log.d("HCE", "4")
        // 5. Get pre-configured response (if any)
        val responseApdu = AndroidHceService.portData[port]
        if(responseApdu == null) Log.d("HCE", "No pre-configured response")

        // 6. Broadcast the APDU command (if needed)
        if (!listenOnlyConfiguredPorts || responseApdu != null) {
            // Check if it's the Get Ticket UID command
            Log.d("HCE", "Received APDU command")

            val isGetTicketUidCommand = commandApdu.copyOfRange(0, 3)
                .contentEquals(byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x00.toByte())) // Assuming this is how you identify the Get Ticket UID command

            // Broadcast the command if it's NOT the Get Ticket UID command OR there's a pre-configured response
            if (!isGetTicketUidCommand || responseApdu != null) {
                Intent().also { intent ->
                    intent.setAction("apduCommand")
                    intent.putExtra("port", port)
                    intent.putExtra(
                        "command", commandApdu.copyOfRange(

                        0,
                        AndroidHceService.aid.size + 5
                    )
                    )
                    intent.putExtra(
                        "data",
                        commandApdu.copyOfRange(AndroidHceService.aid.size + 5, commandApdu.size)
                    )
                    sendBroadcast(intent)
                }
            } else {
                // Log that you're not broadcasting the Get Ticket UID command
                Log.d("HCE", "Not broadcasting Get Ticket UID command, handling in Flutter")
            }
        }


        // 8. Remove the response if not permanent
        if (!permanentApduResponses) AndroidHceService.portData.remove(port)

        Log.d("HCE", "Returning responseApdu + SUCCESS")

        // 9. Return the response with success status
        return (responseApdu ?: ByteArray(0)) + SUCCESS
    }

    override fun onDeactivated(reason: Int) {}
}
