package dev.traceback.indexing;
import java.util.List;
public interface EmbeddingProvider {
    List<float[]> documents(List<String> texts);
    float[] query(String query);
    static void validate(float[] vector,int dimensions) {
        if(vector==null || vector.length!=dimensions) throw new EmbeddingFailure("EMBEDDING_DIMENSIONS");
        double norm=0;
        for(float value:vector) {if(!Float.isFinite(value)) throw new EmbeddingFailure("EMBEDDING_INVALID");norm+=(double)value*value;}
        if(norm==0) throw new EmbeddingFailure("EMBEDDING_INVALID");
    }
    static String literal(float[] vector) {
        var b=new StringBuilder("[");
        for(int i=0;i<vector.length;i++) {if(i>0)b.append(',');b.append(Float.toString(vector[i]));}
        return b.append(']').toString();
    }
}
