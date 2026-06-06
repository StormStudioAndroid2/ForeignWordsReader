# UDPipe Native Integration

This directory vendors UDPipe 1.3.1 source from https://github.com/ufal/udpipe.

- UDPipe source license: MPL-2.0, preserved in `upstream/LICENSE`.
- Bundled model: `english-ewt-ud-2.5-191206.udpipe`, downloaded from `jwijffels/udpipe.models.ud.2.5`.
- Model license: CC BY-NC-SA 4.0, as documented by the model repository and UDPipe model tooling.

The app uses a small C ABI wrapper in `adapter/` so Android JNI and Kotlin/Native iOS cinterop call the same native processing surface.
