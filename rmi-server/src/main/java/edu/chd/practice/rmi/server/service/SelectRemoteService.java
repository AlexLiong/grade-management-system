package edu.chd.practice.rmi.server.service;

import edu.chd.practice.rmi.contract.RemoteServiceException;
import edu.chd.practice.rmi.contract.SelectInterface;
import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.rmi.server.security.AuthorizationService;
import edu.chd.practice.rmi.server.security.RequestAuthenticator;
import edu.chd.practice.rmi.server.sql.DatabaseDialectResolver;
import edu.chd.practice.rmi.server.sql.JdbcPreparedExecutor;
import edu.chd.practice.rmi.server.sql.PreparedSql;
import edu.chd.practice.rmi.server.sql.SafeSqlBuilder;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class SelectRemoteService implements SelectInterface {
    private final RequestAuthenticator authenticator;
    private final AuthorizationService authorization;
    private final SafeSqlBuilder sqlBuilder;
    private final DatabaseDialectResolver dialectResolver;
    private final JdbcPreparedExecutor jdbc;

    public SelectRemoteService(RequestAuthenticator authenticator, AuthorizationService authorization,
                               SafeSqlBuilder sqlBuilder, DatabaseDialectResolver dialectResolver,
                               JdbcPreparedExecutor jdbc) {
        this.authenticator = authenticator;
        this.authorization = authorization;
        this.sqlBuilder = sqlBuilder;
        this.dialectResolver = dialectResolver;
        this.jdbc = jdbc;
    }

    @Override
    public String[][] select(SelectRequest request, InvocationContext context) throws RemoteServiceException {
        requireFresh(authenticator.authenticate(OP_SELECT, request, context));
        authorization.authorizeRead(request, context);
        try {
            PreparedSql prepared = sqlBuilder.select(request, dialectResolver.resolve());
            return jdbc.queryStrings(prepared);
        } catch (IllegalArgumentException exception) {
            throw new RemoteServiceException("SELECT_INVALID", exception.getMessage());
        } catch (DataAccessException exception) {
            throw new RemoteServiceException("SELECT_FAILED", "The parameterized query could not be completed");
        }
    }

    @Override
    public long count(SelectRequest request, InvocationContext context) throws RemoteServiceException {
        requireFresh(authenticator.authenticate(OP_COUNT, request, context));
        authorization.authorizeRead(request, context);
        try {
            return jdbc.queryCount(sqlBuilder.count(request));
        } catch (IllegalArgumentException exception) {
            throw new RemoteServiceException("SELECT_INVALID", exception.getMessage());
        } catch (DataAccessException exception) {
            throw new RemoteServiceException("SELECT_FAILED", "The parameterized count could not be completed");
        }
    }

    private static void requireFresh(boolean fresh) throws RemoteServiceException {
        if (!fresh) throw new RemoteServiceException("AUTH_REPLAY", "Invocation nonce has already been used");
    }
}
