package id.my.kadaferhidup.fraudguard.cases;

/** A signed-in person. Analysts investigate and propose; supervisors approve. Nobody approves their own proposal. */
public record Actor(String id, String name, Role role) {

    public enum Role { ANALYST, SUPERVISOR, SYSTEM }

    @Override
    public String toString() {
        return name + " (" + role.name().toLowerCase() + ")";
    }
}
