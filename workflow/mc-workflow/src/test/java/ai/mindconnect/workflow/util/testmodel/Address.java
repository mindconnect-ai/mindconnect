package ai.mindconnect.workflow.util.testmodel;

import lombok.Data;

@Data
public class Address {
    private final String city;
    private final String zip;

    public Address(String city, String zip) {
        this.city = city;
        this.zip = zip;
    }
}
