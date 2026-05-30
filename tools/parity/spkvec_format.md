# `.spkvec` — portable golden-vector container

A dead-simple binary container for a single dense N-dimensional `float32` array.
It is intentionally minimal so it can be read with ~20 lines of NumPy or C++ and
diffed byte-for-byte in CI. One `.spkvec` file holds exactly one array (one tap of
one parity case).

## Design goals

- **Trivial to parse** in both NumPy and C++ (no compression, no chunking).
- **Self-describing**: shape and dtype live in the header, so a comparator can
  validate shape before touching the data.
- **Endianness-pinned**: data is always little-endian, which is what every CI host
  and Android ARM target we care about uses natively. No byte-swap path needed.
- **Stable on disk**: a regenerated golden for unchanged inputs is byte-identical,
  so `git diff` on `goldens/` is meaningful.

## Byte layout

All multi-byte integers are **little-endian unsigned**. There is no padding /
alignment between fields — fields are written back-to-back in the order below.

| Offset (bytes) | Field        | Type            | Notes                                                  |
|----------------|--------------|-----------------|--------------------------------------------------------|
| 0              | `magic`      | `char[6]`       | ASCII `SPKVEC` (no NUL terminator)                     |
| 6              | `version`    | `uint16`        | format version, currently `1`                          |
| 8              | `dtype`      | `uint8`         | data type code (see table); currently always `1` (f32) |
| 9              | `ndim`       | `uint8`         | number of dimensions, `1..=8`                          |
| 10             | `shape[0]`   | `uint32`        | extent of dimension 0                                  |
| ...            | `shape[i]`   | `uint32`        | one `uint32` per dimension, `ndim` total               |
| 10 + 4·ndim    | `data`       | little-endian   | `prod(shape)` elements of `dtype`, C/row-major order   |

Total header size is `10 + 4 * ndim` bytes. The payload is exactly
`prod(shape) * dtype_size` bytes; the file has no trailer.

### `dtype` codes

| code | meaning   | element size |
|------|-----------|--------------|
| 1    | float32   | 4 bytes      |

Only `float32` is defined today. The engine taps and Python goldens are both
emitted as `float32` — this keeps the container small and matches the precision at
which we assert parity (tolerances below are well above f32 epsilon). The field is
present so the format can grow (e.g. `2` = float64) without a version bump being
strictly required, but readers MUST reject codes they do not understand.

### Data order

`data` is **C-contiguous / row-major**, the NumPy default. For a typical image tap
of shape `(H, W, C)` the fastest-varying axis is `C`, then `W`, then `H`:

```
index(h, w, c) = ((h * W) + w) * C + c
```

The C++ comparator treats the buffer as a flat `float[prod(shape)]` and only uses
`shape` to validate dimensions, so it never needs to reconstruct strides.

## Validation rules for readers

A conformant reader MUST:

1. Reject the file if the first 6 bytes are not `SPKVEC`.
2. Reject `version` it does not support (current readers accept `1`).
3. Reject a `dtype` code it does not understand.
4. Reject `ndim == 0` or `ndim > 8`.
5. Verify the remaining byte count equals `prod(shape) * dtype_size`.

## Reference (pseudocode)

Write:

```
write "SPKVEC"
write u16 version=1
write u8  dtype=1
write u8  ndim
for d in shape: write u32 d
write raw little-endian float32[prod(shape)]
```

Read:

```
assert read(6) == "SPKVEC"
assert read_u16() == 1
dtype = read_u8(); assert dtype == 1
ndim  = read_u8(); assert 1 <= ndim <= 8
shape = [read_u32() for _ in range(ndim)]
data  = read_f32(prod(shape))   # little-endian
assert no bytes remain
```

The canonical implementations are [`spkvec.py`](spkvec.py) (NumPy) and
[`spkvec_io.h`](spkvec_io.h) (C++). They are kept byte-compatible by the round-trip
test in `compare_main.cpp --selftest`.
