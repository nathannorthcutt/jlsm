# Product Quantization — Detail

Extended sections for product-quantization.md.

## current-research

### key-papers

- Jégou, H., Douze, M., & Schmid, C. (2011). Product Quantization for Nearest Neighbor Search. *IEEE TPAMI*, 33(1), 117–128. DOI: 10.1109/TPAMI.2010.57
- Ge, T., He, K., Ke, Q., & Sun, J. (2014). Optimized Product Quantization. *IEEE TPAMI*, 36(4), 744–755.
- André, F., Kermarrec, A.-M., & Le Scouarnec, N. (2021). Accelerated Nearest Neighbor Search with Quick ADC. *ACM TOCS*.
- Douze, M., et al. (2024). The Faiss Library. arXiv:2401.08281.

### active-research-directions

- **4-bit FastScan + reranking**: SIMD-optimized PQ as first-stage filter with full-precision reranking
- **Neural codebook conditioning**: QINCo/QINCo2 learns codebooks conditioned on partial reconstructions — achieves PQ-level speed with RQ-level accuracy
- **Streaming PQ updates**: online codebook adaptation without full retraining (SVD-updating approaches)
- **Hardware-aware PQ**: co-designing quantization parameters for specific SIMD widths and cache sizes

## practical-usage

### when-to-use

- Billion-scale datasets where memory is the binding constraint
- Combined with IVF as IVF-PQ for high-throughput approximate search
- As a first-stage candidate generator followed by reranking with full vectors or SQ codes
- When codebook training is acceptable (batch indexing pipelines, periodic rebuilds)

### when-not-to-use

- Streaming workloads with shifting data distributions — use [scalar-quantization](scalar-quantization.md) or [rabitq](rabitq.md)
- When recall >95% is required without reranking — use SQ8
- Low-dimensional vectors (D<32) — subspace dimension becomes too small for meaningful clustering
- When encoding latency matters (real-time insert paths) — SQ is orders of magnitude faster

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| FAISS | C++/Python | https://github.com/facebookresearch/faiss | Active (Meta) |
| ScaNN | C++/Python | https://github.com/google-research/google-research/tree/master/scann | Active (Google) |
| Hnswlib | C++ | https://github.com/nmslib/hnswlib | Active |
| Vearch | Go | https://github.com/vearch/vearch | Active |
| pqknn | Python | https://github.com/jvdd/pqknn | Research |

## code-skeleton

```java
// Product Quantization — Java pseudocode for jlsm-vector
class ProductQuantizer {
    int M;           // number of subspaces
    int kStar;       // centroids per subspace (typically 256)
    int dsub;        // D / M — subvector dimension
    float[][] codebooks; // [M][kStar * dsub] — flattened centroid arrays

    void train(float[][] trainingVectors) {
        dsub = trainingVectors[0].length / M;
        for (int j = 0; j < M; j++) {
            float[][] subvectors = extractSubvectors(trainingVectors, j, dsub);
            codebooks[j] = kmeans(subvectors, kStar);
        }
    }

    byte[] encode(float[] vector) {
        byte[] code = new byte[M];
        for (int j = 0; j < M; j++) {
            float[] sub = slice(vector, j * dsub, (j + 1) * dsub);
            code[j] = (byte) nearestCentroid(codebooks[j], sub, kStar);
        }
        return code;
    }

    float[] buildDistanceTable(float[] query) {
        // Returns M * kStar distance table
        float[] table = new float[M * kStar];
        for (int j = 0; j < M; j++) {
            float[] qSub = slice(query, j * dsub, (j + 1) * dsub);
            for (int i = 0; i < kStar; i++) {
                table[j * kStar + i] = l2Distance(qSub, centroid(codebooks[j], i, dsub));
            }
        }
        return table;
    }

    float asymmetricDistance(float[] distanceTable, byte[] code) {
        float dist = 0f;
        for (int j = 0; j < M; j++) {
            dist += distanceTable[j * kStar + Byte.toUnsignedInt(code[j])];
        }
        return dist;
    }
}
```
