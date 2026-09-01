package edu.chd.practice.rmi.contract;

import java.io.Serializable;

/** A stable representation used when authenticating an RMI invocation. */
public interface Canonicalizable extends Serializable {
    String canonicalForm();
}
