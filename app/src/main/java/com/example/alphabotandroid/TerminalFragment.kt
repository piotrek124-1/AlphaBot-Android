package com.example.alphabotandroid

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.*
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.fragment.app.Fragment
import com.example.alphabotandroid.SerialService.SerialBinder
import com.example.alphabotandroid.TextUtil.HexWatcher
import java.util.*

/*
MIT License
       Copyright (c) 2019 Kai Morich
       Permission is hereby granted, free of charge, to any person obtaining a copy
       of this software and associated documentation files (the "Software"), to deal
       in the Software without restriction, including without limitation the rights
       to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
       copies of the Software, and to permit persons to whom the Software is
       furnished to do so, subject to the following conditions:
       The above copyright notice and this permission notice shall be included in all
       copies or substantial portions of the Software.
       THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
       IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
       FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
       AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
       LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
       OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
       SOFTWARE.
*/
class TerminalFragment : Fragment(), ServiceConnection, SerialListener {
    private enum class Connected {
        False, Pending, True
    }

    private var deviceAddress: String? = null
    private var service: SerialService? = null
    private var receiveText: TextView? = null
    private var sendText: TextView? = null
    private var hexWatcher: HexWatcher? = null
    private var connected = Connected.False
    private var initialStart = true
    private var hexEnabled = true
    private var pendingNewline = false
    private var newline = TextUtil.newline_crlf
    private var lastOutput: String? = null

    /*
     * Lifecycle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        retainInstance = true
        deviceAddress = arguments!!.getString("device")
    }

    override fun onDestroy() {
        if (connected != Connected.False) disconnect()
        activity!!.stopService(Intent(activity, SerialService::class.java))
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (service != null) service!!.attach(this) else activity!!.startService(Intent(activity, SerialService::class.java)) // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    override fun onStop() {
        if (service != null && !activity!!.isChangingConfigurations) service!!.detach()
        super.onStop()
    }

    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        getActivity()!!.bindService(Intent(getActivity(), SerialService::class.java), this, Context.BIND_AUTO_CREATE)
    }

    override fun onDetach() {
        try {
            activity!!.unbindService(this)
        } catch (ignored: Exception) {
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        if (initialStart && service != null) {
            initialStart = false
            activity!!.runOnUiThread { connect() }
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as SerialBinder).service
        service!!.attach(this)
        if (initialStart && isResumed) {
            initialStart = false
            activity!!.runOnUiThread { connect() }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    /*
     * UI
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment, container, false)
        receiveText = view.findViewById(R.id.received_text) // TextView performance decreases with number of spans
        receiveText!!.setTextColor(resources.getColor(R.color.white)) // set as default color to reduce number of spans
        receiveText!!.setMovementMethod(ScrollingMovementMethod.getInstance())
        sendText = view.findViewById(R.id.send_text)
        hexWatcher = HexWatcher(sendText)
        hexWatcher!!.enable(hexEnabled)
        sendText!!.addTextChangedListener(hexWatcher)
        sendText!!.setHint(if (hexEnabled) "HEX mode" else "")
        val sendBtn = view.findViewById<View>(R.id.send_button)
        sendBtn.setOnClickListener { v: View? -> send(sendText!!.getText().toString()) }
        val up = view.findViewById<Button>(R.id.forward)
        val down = view.findViewById<Button>(R.id.down)
        val left = view.findViewById<Button>(R.id.left)
        val right = view.findViewById<Button>(R.id.right)
        val avoidance = view.findViewById<ImageButton>(R.id.autonomus_mode)
        var speed = view.findViewById<SeekBar>(R.id.seekBar)
        val check = view.findViewById<CheckBox>(R.id.checkBox)
        val stop = view.findViewById<ImageButton>(R.id.stopButton)
        speed.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {

            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {

            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                var progressHex = "02 01 "
                progressHex += Integer.toHexString(seekBar.progress)
                progressHex += " 03"
                send(progressHex)
            }
        })
        up.setOnTouchListener { v: View?, event: MotionEvent ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                send("02 0A C8 03")
            } else if (event.action == MotionEvent.ACTION_UP) {
                send("02 01 00 03")
            }
            false
        }
        down.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                send("02 0D C8 03")
            } else if (event.action == MotionEvent.ACTION_UP) {
                send("02 01 00 03")
            }
            false
        }
        left.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                send("02 0B C8 03")
            } else if (event.action == MotionEvent.ACTION_UP) {
                send("02 01 00 03")
            }
            false
        }
        right.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                send("02 0C C8 03")
            } else if (event.action == MotionEvent.ACTION_UP) {
                send("02 01 00 03")
            }
            false
        }
        stop.setOnClickListener { send("02 02 00 03") }
        avoidance.setOnClickListener { avoidance(check) }
        return view
    }

    private fun avoidance(check: CheckBox) {
        send("02 00 C8 03")
        if (check.isChecked()) {
            check.setChecked(false)
        } else {
            check.setChecked(true)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
        menu.findItem(R.id.hex).isChecked = hexEnabled
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        return if (id == R.id.clear) {
            receiveText!!.text = ""
            true
        } else if (id == R.id.newline) {
            val newlineNames = resources.getStringArray(R.array.newline_names)
            val newlineValues = resources.getStringArray(R.array.newline_values)
            val pos = Arrays.asList(*newlineValues).indexOf(newline)
            val builder = AlertDialog.Builder(activity)
            builder.setTitle("Newline")
            builder.setSingleChoiceItems(newlineNames, pos) { dialog: DialogInterface, item1: Int ->
                newline = newlineValues[item1]
                dialog.dismiss()
            }
            builder.create().show()
            true
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled
            sendText!!.text = ""
            hexWatcher!!.enable(hexEnabled)
            sendText!!.hint = if (hexEnabled) "HEX mode" else ""
            item.isChecked = hexEnabled
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    /*
     * Serial + UI
     */
    private fun connect() {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
            status("connecting...")
            connected = Connected.Pending
            val socket = SerialSocket(activity!!.applicationContext, device)
            service!!.connect(socket)
        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.False
        service!!.disconnect()
    }

    fun send(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val msg: String
            val data: ByteArray
            if (hexEnabled) {
                val sb = StringBuilder()
                TextUtil.toHexString(sb, TextUtil.fromHexString(str))
                TextUtil.toHexString(sb, newline.toByteArray())
                msg = sb.toString()
                data = TextUtil.fromHexString(msg)
            } else {
                msg = str
                data = (str + newline).toByteArray()
            }
            val spn = SpannableStringBuilder("""
    $msg
    
    """.trimIndent())
            spn.setSpan(ForegroundColorSpan(resources.getColor(R.color.blue)), 0, spn.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            receiveText!!.append(spn)
            service!!.write(data)
        } catch (e: Exception) {
            onSerialIoError(e)
        }
    }

    private fun receive(data: ByteArray) {
        if (!hexEnabled) {
            receiveText!!.append("""
    ${TextUtil.toHexString(data)}
    
    """.trimIndent())
        } else {
            var msg = String(data)
            lastOutput = msg
            if (newline == TextUtil.newline_crlf && msg.length > 0) {
                // don't show CR as ^M if directly before LF
                msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf)
                // special handling if CR and LF come in separate fragments
                if (pendingNewline && msg[0] == '\n') {
                    val edt = receiveText!!.editableText
                    if (edt != null && edt.length > 1) edt.replace(edt.length - 2, edt.length, "")
                }
                pendingNewline = msg[msg.length - 1] == '\r'
            }
            receiveText!!.append(TextUtil.toCaretString(msg, newline.length != 0))
        }
    }

    private fun status(str: String) {
        val spn = SpannableStringBuilder("""
    $str
    
    """.trimIndent())
        spn.setSpan(ForegroundColorSpan(resources.getColor(R.color.blue)), 0, spn.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        receiveText!!.append(spn)
    }

    /*
     * SerialListener
     */
    override fun onSerialConnect() {
        status("connected")
        connected = Connected.True
    }

    override fun onSerialConnectError(e: Exception) {
        status("connection failed: " + e.message)
        disconnect()
    }

    override fun onSerialRead(data: ByteArray) {
        receive(data)
    }

    override fun onSerialIoError(e: Exception) {
        status("connection lost: " + e.message)
        disconnect()
    }

    companion object {
        const val TAG = "Press"
    }
}