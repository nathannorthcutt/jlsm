# Panama FFM Inline Machine Code — Detail

Extended content for [panama-ffm-inline-machine-code.md](panama-ffm-inline-machine-code.md).

## full-escape-masking-pipeline

1. **Character scan**: SIMD compare 64 bytes against `'"'` → 64-bit quote bitmap
2. **Backslash handling**: SIMD compare against `'\'` → backslash bitmap.
   Identify odd-length sequences via add-carry propagation. Build `odd_ends`.
3. **Unescaped quotes**: `quote_bits = quote_bits & ~odd_ends`
4. **Prefix-XOR**: `quote_mask = PCLMULQDQ(quote_bits, 0xFFFF...)`
5. **Cross-block carry**: `quote_mask ^= prev_iter_inside_quote`
6. **Result**: `quote_mask` has 1-bits for all bytes inside quoted strings

## panama-ffm-executable-memory-pipeline

The Java-side pipeline to invoke embedded machine code:

```
byte[] machineCode → mmap(RW) → memcpy → mprotect(RX) → downcallHandle → invoke
```

### step-by-step

1. **Embed machine code** as `static final byte[]` constants. Each constant is
   a complete, position-independent function conforming to the platform ABI.

2. **Allocate anonymous memory** via Panama downcall to `mmap`:
   ```java
   var mmap = linker.downcallHandle(
       libc.find("mmap").orElseThrow(),
       FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT,
                             JAVA_INT, JAVA_INT, JAVA_LONG));
   // MAP_PRIVATE | MAP_ANONYMOUS, PROT_READ | PROT_WRITE
   MemorySegment code = (MemorySegment) mmap.invokeExact(
       MemorySegment.NULL, (long) machineCode.length,
       0x01 | 0x02, 0x02 | 0x20, -1, 0L);
   ```

3. **Copy machine code** into the allocated segment:
   ```java
   code.copyFrom(MemorySegment.ofArray(machineCode));
   ```

4. **Set executable permission** via `mprotect`:
   ```java
   var mprotect = linker.downcallHandle(
       libc.find("mprotect").orElseThrow(),
       FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));
   mprotect.invokeExact(code, (long) machineCode.length, 0x01 | 0x04);
   // PROT_READ | PROT_EXEC (W^X: no longer writable)
   ```

5. **Create downcall handle** for the machine code function:
   ```java
   MethodHandle quoteMask = linker.downcallHandle(
       code,  // the executable MemorySegment IS the function pointer
       FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
   ```

6. **Invoke** on hot path:
   ```java
   long mask = (long) quoteMask.invokeExact(inputSegment, blockOffset);
   ```

## machine-code-embedding-steps

1. **Assemble** the target function offline: `nasm` (x86) or `as` (ARM)
2. **Extract** raw bytes: `objcopy -O binary`
3. **Encode** as Java hex byte array constant
4. **Verify** position-independence: no absolute addresses, no relocations
5. **Document** the ABI contract: registers for arguments/return values

## edge-cases-and-gotchas

- **W^X enforcement**: modern OSes forbid RWX memory. Always mmap RW, copy,
  then mprotect to RX. Never attempt RWX.
- **macOS MAP_JIT**: on Apple Silicon, requires `MAP_JIT` flag (0x0800) and
  `pthread_jit_write_protect_np()` toggling between write and execute modes
- **Position-independent code**: must use only RIP-relative (x86) or
  PC-relative (ARM) addressing. No absolute addresses.
- **ABI compliance**: System V AMD64 (rdi, rsi, rdx → rax) or AAPCS64
  (x0-x7 → x0)
- **Page alignment**: `mprotect` operates on page-aligned regions; mmap output
  is automatically page-aligned
- **Restricted operations**: `Linker.downcallHandle` with raw address requires
  `--enable-native-access=<module>` JVM flag
- **Cleanup**: mmap'd segment must be munmap'd on shutdown

## code-skeleton

```java
class PclmulqdqQuoteMask {
    // Pre-assembled x86-64 machine code: System V ABI
    // rdi = input pointer, rsi = block offset
    // Returns: quote mask in rax
    private static final byte[] X86_64_CODE = {
        // movdqu xmm0, [rdi+rsi]       ; load 16 bytes
        // pcmpeqb xmm0, xmm_quote_char ; compare against '"'
        // pmovmskb eax, xmm0           ; extract mask
        // ... (backslash handling) ...
        // pclmulqdq xmm0, xmm1, 0x00   ; prefix-XOR
        // movq rax, xmm0               ; extract result
        // ret
        // (actual bytes would go here)
    };

    private static final byte[] AARCH64_CODE = {
        // ldr q0, [x0, x1]             ; load 16 bytes
        // cmeq v0.16b, v0.16b, v_quote ; compare against '"'
        // ... (backslash handling) ...
        // pmull v0.1q, v0.1d, v1.1d    ; prefix-XOR
        // mov x0, v0.d[0]              ; extract result
        // ret
    };

    private final MethodHandle quoteMaskFn;

    PclmulqdqQuoteMask() {
        String arch = System.getProperty("os.arch");
        byte[] code = switch (arch) {
            case "amd64", "x86_64" -> X86_64_CODE;
            case "aarch64"         -> AARCH64_CODE;
            default -> null;
        };
        if (code != null) {
            this.quoteMaskFn = loadExecutable(code);
        } else {
            this.quoteMaskFn = null; // fall back to Vector API / scalar
        }
    }

    private static MethodHandle loadExecutable(byte[] code) {
        var linker = Linker.nativeLinker();
        var libc = linker.defaultLookup();
        // 1. mmap anonymous RW memory
        // 2. copy code bytes
        // 3. mprotect to RX
        // 4. return linker.downcallHandle(segment, FunctionDescriptor.of(...))
        throw new UnsupportedOperationException("see full implementation");
    }

    long computeQuoteMask(MemorySegment input, long offset) throws Throwable {
        if (quoteMaskFn != null) {
            return (long) quoteMaskFn.invokeExact(input, offset);
        }
        return scalarPrefixXor(input, offset); // fallback
    }
}
```
