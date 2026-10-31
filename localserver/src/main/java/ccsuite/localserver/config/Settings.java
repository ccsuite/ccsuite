package ccsuite.localserver.config;

import jakarta.xml.bind.annotation.*;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

/** Mapped to {@code CCAPIsLocalServerSettings.xml} in the user home directory. */
@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Accessors(fluent = true, chain = true)
@XmlRootElement(name = "CCAPIsLocalServerSettings")
@XmlAccessorType(XmlAccessType.FIELD)
public class Settings {

    @XmlAttribute(name = "port", required = false)
    Integer port;

    @XmlAttribute(name = "usageRefreshIntervalSecs", required = false)
    Integer usageRefreshIntervalSecs;

    public int port() { return port != null && port > 0 ? port : 8790; }

    public int usageRefreshIntervalSecs() {
        return usageRefreshIntervalSecs != null && usageRefreshIntervalSecs > 0 ? usageRefreshIntervalSecs : 60;
    }
}
