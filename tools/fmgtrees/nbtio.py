"""
Minimal NBT reader/writer -- just enough for WorldEdit schematics and vanilla
structure files. No dependencies on purpose: this lives in the repo and has to
run from a bare Python install.

Tags are represented as plain Python values, with the tag type carried
alongside where it cannot be inferred:

    TAG_Compound -> dict[str, value]
    TAG_List     -> List (a list subclass that remembers its element type)
    TAG_Byte/Short/Int/Long     -> Int (an int subclass that remembers width)
    TAG_Float/Double            -> Float (a float subclass, likewise)
    TAG_String                  -> str
    TAG_ByteArray/IntArray/LongArray -> Array (a list subclass)

Reading infers all of this; writing uses it. A plain Python int written without
a wrapper defaults to TAG_Int, a plain float to TAG_Double.
"""

import gzip
import struct

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class Int(int):
    """An int that remembers which NBT integer tag it came from."""
    tag = TAG_INT

    def __new__(cls, value, tag=TAG_INT):
        self = super().__new__(cls, value)
        self.tag = tag
        return self


class Float(float):
    tag = TAG_DOUBLE

    def __new__(cls, value, tag=TAG_DOUBLE):
        self = super().__new__(cls, value)
        self.tag = tag
        return self


class List(list):
    """A TAG_List that remembers its element type (needed when empty)."""

    def __init__(self, items=(), element_tag=TAG_END):
        super().__init__(items)
        self.element_tag = element_tag


class Array(list):
    """TAG_Byte_Array / TAG_Int_Array / TAG_Long_Array."""

    def __init__(self, items=(), tag=TAG_BYTE_ARRAY):
        super().__init__(items)
        self.tag = tag


def byte(v):
    return Int(v, TAG_BYTE)


def short(v):
    return Int(v, TAG_SHORT)


def long(v):
    return Int(v, TAG_LONG)


# ---- reading -------------------------------------------------------------


class _Reader:
    def __init__(self, data):
        self.d = data
        self.i = 0

    def take(self, n):
        i = self.i
        self.i = i + n
        if self.i > len(self.d):
            raise EOFError("truncated NBT")
        return self.d[i:self.i]

    def u1(self):
        return self.take(1)[0]

    def i1(self):
        return struct.unpack(">b", self.take(1))[0]

    def i2(self):
        return struct.unpack(">h", self.take(2))[0]

    def u2(self):
        return struct.unpack(">H", self.take(2))[0]

    def i4(self):
        return struct.unpack(">i", self.take(4))[0]

    def i8(self):
        return struct.unpack(">q", self.take(8))[0]

    def f4(self):
        return struct.unpack(">f", self.take(4))[0]

    def f8(self):
        return struct.unpack(">d", self.take(8))[0]

    def string(self):
        return self.take(self.u2()).decode("utf-8", "replace")

    def payload(self, tag):
        if tag == TAG_BYTE:
            return Int(self.i1(), TAG_BYTE)
        if tag == TAG_SHORT:
            return Int(self.i2(), TAG_SHORT)
        if tag == TAG_INT:
            return Int(self.i4(), TAG_INT)
        if tag == TAG_LONG:
            return Int(self.i8(), TAG_LONG)
        if tag == TAG_FLOAT:
            return Float(self.f4(), TAG_FLOAT)
        if tag == TAG_DOUBLE:
            return Float(self.f8(), TAG_DOUBLE)
        if tag == TAG_BYTE_ARRAY:
            n = self.i4()
            return Array(struct.unpack(">%db" % n, self.take(n)), TAG_BYTE_ARRAY)
        if tag == TAG_STRING:
            return self.string()
        if tag == TAG_LIST:
            et = self.u1()
            n = self.i4()
            return List([self.payload(et) for _ in range(max(0, n))], et)
        if tag == TAG_COMPOUND:
            out = {}
            while True:
                t = self.u1()
                if t == TAG_END:
                    return out
                # name first: Python evaluates an assignment's value before its
                # subscript key, which would read these two out of order.
                key = self.string()
                out[key] = self.payload(t)
        if tag == TAG_INT_ARRAY:
            n = self.i4()
            return Array(struct.unpack(">%di" % n, self.take(4 * n)), TAG_INT_ARRAY)
        if tag == TAG_LONG_ARRAY:
            n = self.i4()
            return Array(struct.unpack(">%dq" % n, self.take(8 * n)), TAG_LONG_ARRAY)
        raise ValueError("unknown NBT tag %d" % tag)


def loads(data):
    """Read a root compound. Returns (name, value)."""
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    r = _Reader(data)
    t = r.u1()
    if t != TAG_COMPOUND:
        raise ValueError("root tag is not a compound (got %d)" % t)
    return r.string(), r.payload(TAG_COMPOUND)


def load(path):
    with open(path, "rb") as fh:
        return loads(fh.read())


# ---- writing -------------------------------------------------------------


def _tag_of(v):
    if isinstance(v, (Int, Float)):
        return v.tag
    if isinstance(v, Array):
        return v.tag
    if isinstance(v, List):
        return TAG_LIST
    if isinstance(v, bool):
        return TAG_BYTE
    if isinstance(v, int):
        return TAG_INT
    if isinstance(v, float):
        return TAG_DOUBLE
    if isinstance(v, str):
        return TAG_STRING
    if isinstance(v, dict):
        return TAG_COMPOUND
    if isinstance(v, (list, tuple)):
        return TAG_LIST
    raise TypeError("cannot write %r as NBT" % type(v))


def _string(out, s):
    b = s.encode("utf-8")
    out += struct.pack(">H", len(b))
    out += b


def _payload(out, tag, v):
    if tag == TAG_BYTE:
        out += struct.pack(">b", int(v))
    elif tag == TAG_SHORT:
        out += struct.pack(">h", int(v))
    elif tag == TAG_INT:
        out += struct.pack(">i", int(v))
    elif tag == TAG_LONG:
        out += struct.pack(">q", int(v))
    elif tag == TAG_FLOAT:
        out += struct.pack(">f", float(v))
    elif tag == TAG_DOUBLE:
        out += struct.pack(">d", float(v))
    elif tag == TAG_BYTE_ARRAY:
        out += struct.pack(">i", len(v))
        out += struct.pack(">%db" % len(v), *v) if v else b""
    elif tag == TAG_STRING:
        _string(out, v)
    elif tag == TAG_LIST:
        et = v.element_tag if isinstance(v, List) else (_tag_of(v[0]) if v else TAG_END)
        out += struct.pack(">Bi", et, len(v))
        for item in v:
            _payload(out, et, item)
    elif tag == TAG_COMPOUND:
        for k, item in v.items():
            t = _tag_of(item)
            out += bytes((t,))
            _string(out, k)
            _payload(out, t, item)
        out += b"\x00"
    elif tag == TAG_INT_ARRAY:
        out += struct.pack(">i", len(v))
        out += struct.pack(">%di" % len(v), *v) if v else b""
    elif tag == TAG_LONG_ARRAY:
        out += struct.pack(">i", len(v))
        out += struct.pack(">%dq" % len(v), *v) if v else b""
    else:
        raise ValueError("unknown NBT tag %d" % tag)


def dumps(value, name=""):
    out = bytearray(b"\x0a")
    _string(out, name)
    _payload(out, TAG_COMPOUND, value)
    return bytes(out)


def dump(path, value, name="", compress=True):
    raw = dumps(value, name)
    if compress:
        # mtime=0 so identical input gives byte-identical output
        with open(path, "wb") as fh:
            with gzip.GzipFile(fileobj=fh, mode="wb", mtime=0) as gz:
                gz.write(raw)
    else:
        with open(path, "wb") as fh:
            fh.write(raw)
