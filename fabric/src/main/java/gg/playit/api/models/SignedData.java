package gg.playit.api.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import gg.playit.playitfabric.utils.DecoderException;
import gg.playit.playitfabric.utils.Hex;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SignedData {
    @JsonProperty
    public String data;

    public byte[] decode() throws DecoderException {
        return Hex.decodeHex(data);
    }
}
