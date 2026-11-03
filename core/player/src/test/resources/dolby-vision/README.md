RPUs from dovi_tool's test assets (https://github.com/quietvoid/dovi_tool,
assets/tests, MIT licence, Copyright (c) quietvoid): profile 7 originals with
a full (`fel_orig.bin`) and a minimal (`mel_orig.bin`) enhancement layer, and
what dovi_tool makes of them as profile 8.1 with the mapping kept
(`*_to_81.bin`). `fel_to_81_mapping_removed.bin` is the full enhancement layer
RPU converted by dovi_tool 2.3.1 itself in mode 2 (`dovi_tool editor -j` with
`{"mode": 2}`), which also sets the mapping curves to no-op. Each file is a start code followed by the RPU from its
prefix byte 0x19 on, with emulation prevention bytes.
