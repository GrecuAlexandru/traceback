package dev.traceback.indexing;
import java.util.UUID;
public record IndexMessage(UUID event,UUID job,UUID organization,UUID document,UUID revision,UUID generation) {
    public String encode() {return "v2:"+event+":"+job+":"+organization+":"+document+":"+revision+":"+generation;}
    public static IndexMessage parse(String body) {
        if(body==null || body.length()!=224) throw new IllegalArgumentException("Invalid indexing message");
        String[] fields=body.split(":",-1);if(fields.length!=7 || !fields[0].equals("v2")) throw new IllegalArgumentException("Invalid indexing message");
        UUID[] ids=new UUID[6];for(int i=0;i<6;i++) {ids[i]=UUID.fromString(fields[i+1]);if(!ids[i].toString().equals(fields[i+1]))throw new IllegalArgumentException("Invalid indexing reference");}
        return new IndexMessage(ids[0],ids[1],ids[2],ids[3],ids[4],ids[5]);
    }
}
