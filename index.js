const ReactNative = require('react-native')
const { Buffer } = require('buffer')
const { NativeModules, DeviceEventEmitter } = ReactNative
const BluetoothSerial = NativeModules.BluetoothSerial

BluetoothSerial.on = (eventName, handler) => {
  DeviceEventEmitter.addListener(eventName, handler)
}


BluetoothSerial.removeListener = (eventName, handler) => {
  DeviceEventEmitter.removeListener(eventName, handler)
}

BluetoothSerial.write = (data) => {
  if (typeof data === 'string') {
    data = new Buffer(data)
  }
  return BluetoothSerial.writeToDevice(data.toString('base64'))
}

module.exports = BluetoothSerial
