package edu.chd.practice.rmi.contract;

public final class RmiBindings {
    public static final String SELECT = "GradeSelectService";
    public static final String MANIPULATION = "GradeManipulationService";
    public static final String HEALTH = "GradeHealthService";
    public static final String INTEGRITY = "GradeIntegrityService";

    public static final int DEFAULT_REGISTRY_PORT = 1199;
    public static final int DEFAULT_SERVICE_PORT = 1200;

    private RmiBindings() {
    }
}
