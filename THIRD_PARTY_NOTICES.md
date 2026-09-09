# Third-party notices

This project implements the inference sequence published by
[High-Logic/Genie-TTS v2.0.2](https://github.com/High-Logic/Genie-TTS/tree/v2.0.2),
which is licensed under the MIT License. Copyright (c) 2025 High_Logic.

The Android runtime uses [Microsoft ONNX Runtime](https://github.com/microsoft/onnxruntime),
licensed under the MIT License. Genie-TTS is based on
[GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS).

The runtime English frontend includes the Carnegie Mellon Pronouncing Dictionary
([CMUdict](https://github.com/cmusphinx/cmudict)), distributed under its BSD-style
license. The runtime Japanese frontend uses the NAIST-jdic/Open JTalk dictionary
version 1.11 and [ayutaz/openjtalk-native](https://github.com/ayutaz/openjtalk-native)
version 1.0.1, distributed under their respective BSD-style licenses. The Android
binary and dictionary are pinned and reproducibly prepared by the repository's
`prepare-openjtalk-assets.yml` workflow.

Model weights, reference audio, character names and voice data remain subject
to their respective licenses and rights. The generated benchmark package is
intended for local, non-commercial technical evaluation.
