package ai.mindconnect.vectorstore;

/** The vector math the heap backends share: normalise once on the way in, then a dot product is the cosine. */
public final class Vectors {

    private Vectors() {
    }

    /** A copy of {@code vector} scaled to length 1; an all-zero vector comes back as an all-zero copy. */
    public static float[] normalised(float[] vector) {
        double norm = 0;
        for (float v : vector) norm += v * v;
        norm = Math.sqrt(norm);
        float[] copy = vector.clone();
        if (norm == 0) return copy;
        for (int i = 0; i < copy.length; i++) copy[i] = (float) (copy[i] / norm);
        return copy;
    }

    /** The dot product — the cosine similarity when both vectors are normalised. */
    public static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }
}
