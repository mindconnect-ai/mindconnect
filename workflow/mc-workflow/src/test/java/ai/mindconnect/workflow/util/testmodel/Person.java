package ai.mindconnect.workflow.util.testmodel;

import lombok.Data;

import java.util.List;

@Data
public class Person {
    private final String name;
    private final int age;
    private final Address address;
    private final List<Address> addresses;

    public Person(String name, int age, Address address) {
        this.name = name;
        this.age = age;
        this.address = address;
        this.addresses = List.of();
    }

    public Person(String name, int age, Address address, List<Address> addresses) {
        this.name = name;
        this.age = age;
        this.address = address;
        this.addresses = addresses;
    }
}
