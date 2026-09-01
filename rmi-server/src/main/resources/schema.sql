CREATE TABLE IF NOT EXISTS roles (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL
);

CREATE TABLE IF NOT EXISTS permissions (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(96) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL
);

CREATE TABLE IF NOT EXISTS orgs (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    parent_id VARCHAR(64),
    CONSTRAINT fk_org_parent FOREIGN KEY (parent_id) REFERENCES orgs(id)
);

CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    email VARCHAR(160),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    student_id VARCHAR(64),
    teacher_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id VARCHAR(64) NOT NULL,
    role_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES roles(id)
);

CREATE TABLE IF NOT EXISTS role_permissions (
    role_id VARCHAR(64) NOT NULL,
    permission_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES roles(id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permissions(id)
);

CREATE TABLE IF NOT EXISTS user_permissions (
    user_id VARCHAR(64) NOT NULL,
    permission_id VARCHAR(64) NOT NULL,
    granted BOOLEAN NOT NULL,
    granted_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, permission_id),
    CONSTRAINT fk_user_permission_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_permission_permission FOREIGN KEY (permission_id) REFERENCES permissions(id)
);

CREATE TABLE IF NOT EXISTS students (
    id VARCHAR(64) PRIMARY KEY,
    student_no VARCHAR(32) NOT NULL UNIQUE,
    user_id VARCHAR(64) UNIQUE,
    name VARCHAR(128) NOT NULL,
    gender VARCHAR(16),
    admission_year INT NOT NULL,
    class_name VARCHAR(128) NOT NULL,
    major VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_student_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS teachers (
    id VARCHAR(64) PRIMARY KEY,
    teacher_no VARCHAR(32) NOT NULL UNIQUE,
    user_id VARCHAR(64) UNIQUE,
    name VARCHAR(128) NOT NULL,
    title VARCHAR(64),
    org_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_teacher_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_teacher_org FOREIGN KEY (org_id) REFERENCES orgs(id)
);

CREATE TABLE IF NOT EXISTS courses (
    id VARCHAR(64) PRIMARY KEY,
    course_code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    credit DECIMAL(4,1) NOT NULL,
    hours INT NOT NULL,
    org_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_course_org FOREIGN KEY (org_id) REFERENCES orgs(id),
    CONSTRAINT ck_course_credit CHECK (credit > 0),
    CONSTRAINT ck_course_hours CHECK (hours > 0)
);

CREATE TABLE IF NOT EXISTS course_offerings (
    id VARCHAR(64) PRIMARY KEY,
    course_id VARCHAR(64) NOT NULL,
    teacher_id VARCHAR(64) NOT NULL,
    academic_year VARCHAR(16) NOT NULL,
    semester INT NOT NULL,
    class_name VARCHAR(128) NOT NULL,
    capacity INT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    CONSTRAINT uq_offering UNIQUE (course_id, teacher_id, academic_year, semester, class_name),
    CONSTRAINT fk_offering_course FOREIGN KEY (course_id) REFERENCES courses(id),
    CONSTRAINT fk_offering_teacher FOREIGN KEY (teacher_id) REFERENCES teachers(id),
    CONSTRAINT ck_offering_semester CHECK (semester IN (1, 2, 3)),
    CONSTRAINT ck_offering_capacity CHECK (capacity > 0)
);

CREATE VIEW IF NOT EXISTS teacher_history_courses AS
SELECT o.id,
       o.course_id,
       o.teacher_id,
       c.course_code,
       c.name AS course_name,
       LOWER(CONCAT(c.course_code, ' ', c.name)) AS search_text,
       o.academic_year,
       o.semester,
       o.class_name
FROM course_offerings o
JOIN courses c ON c.id = o.course_id
WHERE o.status = 'CLOSED';

CREATE TABLE IF NOT EXISTS enrollments (
    id VARCHAR(64) PRIMARY KEY,
    offering_id VARCHAR(64) NOT NULL,
    student_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ENROLLED',
    enrolled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_enrollment UNIQUE (offering_id, student_id),
    CONSTRAINT fk_enrollment_offering FOREIGN KEY (offering_id) REFERENCES course_offerings(id),
    CONSTRAINT fk_enrollment_student FOREIGN KEY (student_id) REFERENCES students(id)
);

CREATE TABLE IF NOT EXISTS grading_schemes (
    id VARCHAR(64) PRIMARY KEY,
    offering_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    total_weight DECIMAL(6,3) NOT NULL DEFAULT 100.000,
    version INT NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT uq_scheme_version UNIQUE (offering_id, version),
    CONSTRAINT fk_scheme_offering FOREIGN KEY (offering_id) REFERENCES course_offerings(id),
    CONSTRAINT ck_scheme_weight CHECK (total_weight = 100.000)
);

CREATE TABLE IF NOT EXISTS grading_weights (
    id VARCHAR(64) PRIMARY KEY,
    scheme_id VARCHAR(64) NOT NULL,
    item_code VARCHAR(32) NOT NULL,
    item_name VARCHAR(128) NOT NULL,
    weight DECIMAL(6,3) NOT NULL,
    max_score DECIMAL(7,2) NOT NULL DEFAULT 100,
    sort_order INT NOT NULL,
    CONSTRAINT uq_weight_item UNIQUE (scheme_id, item_code),
    CONSTRAINT fk_weight_scheme FOREIGN KEY (scheme_id) REFERENCES grading_schemes(id),
    CONSTRAINT ck_weight CHECK (weight > 0 AND weight <= 100)
);

CREATE TABLE IF NOT EXISTS grades (
    id VARCHAR(64) PRIMARY KEY,
    enrollment_id VARCHAR(64) NOT NULL,
    scheme_id VARCHAR(64) NOT NULL,
    score_ciphertext VARCHAR(2048) NOT NULL,
    score_nonce VARCHAR(256) NOT NULL,
    score_integrity VARCHAR(512) NOT NULL,
    key_version INT NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 1,
    submitted_by VARCHAR(64),
    submitted_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_grade UNIQUE (enrollment_id, scheme_id),
    CONSTRAINT fk_grade_enrollment FOREIGN KEY (enrollment_id) REFERENCES enrollments(id),
    CONSTRAINT fk_grade_scheme FOREIGN KEY (scheme_id) REFERENCES grading_schemes(id),
    CONSTRAINT fk_grade_submitter FOREIGN KEY (submitted_by) REFERENCES users(id),
    CONSTRAINT ck_grade_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'REVOKED')),
    CONSTRAINT ck_grade_version CHECK (version > 0)
);

CREATE VIEW IF NOT EXISTS teacher_course_historical_grades AS
SELECT g.id,
       g.enrollment_id,
       g.scheme_id,
       g.score_ciphertext,
       g.score_nonce,
       g.score_integrity,
       g.version,
       g.submitted_at,
       g.updated_at,
       e.student_id,
       s.student_no,
       s.name AS student_name,
       o.id AS offering_id,
       o.course_id,
       o.teacher_id,
       o.academic_year,
       o.semester
FROM grades g
JOIN enrollments e ON e.id = g.enrollment_id
JOIN students s ON s.id = e.student_id
JOIN course_offerings o ON o.id = e.offering_id
WHERE g.status = 'SUBMITTED'
  AND o.status = 'CLOSED';

CREATE TABLE IF NOT EXISTS grade_history (
    id VARCHAR(64) PRIMARY KEY,
    grade_id VARCHAR(64) NOT NULL,
    action VARCHAR(48) NOT NULL,
    original_ciphertext VARCHAR(2048) NOT NULL,
    original_nonce VARCHAR(256) NOT NULL,
    original_integrity VARCHAR(512) NOT NULL,
    reason VARCHAR(1024),
    scope VARCHAR(24) NOT NULL,
    batch_id VARCHAR(128) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS grade_analyses (
    id VARCHAR(64) PRIMARY KEY,
    offering_id VARCHAR(64) NOT NULL,
    average_score DECIMAL(7,2),
    max_score DECIMAL(7,2),
    min_score DECIMAL(7,2),
    pass_rate DECIMAL(6,3),
    distribution_json TEXT,
    analysis_text TEXT,
    generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_analysis_offering UNIQUE (offering_id),
    CONSTRAINT fk_analysis_offering FOREIGN KEY (offering_id) REFERENCES course_offerings(id)
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id VARCHAR(64) PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    table_name VARCHAR(64) NOT NULL,
    record_key VARCHAR(256),
    success BOOLEAN NOT NULL,
    detail VARCHAR(2048),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS alerts (
    id VARCHAR(64) PRIMARY KEY,
    type VARCHAR(64) NOT NULL,
    severity VARCHAR(24) NOT NULL,
    message VARCHAR(2048) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    related_table VARCHAR(64),
    related_id VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS grade_exceptions (
    id VARCHAR(64) PRIMARY KEY,
    enrollment_id VARCHAR(64) NOT NULL,
    type VARCHAR(64) NOT NULL,
    description VARCHAR(2048) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    reported_by VARCHAR(64) NOT NULL,
    handled_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handled_at TIMESTAMP,
    CONSTRAINT fk_exception_enrollment FOREIGN KEY (enrollment_id) REFERENCES enrollments(id)
);

CREATE TABLE IF NOT EXISTS reversion_requests (
    id VARCHAR(64) PRIMARY KEY,
    request_no VARCHAR(64) NOT NULL UNIQUE,
    scope VARCHAR(24) NOT NULL,
    target_filter TEXT NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    requested_by VARCHAR(64) NOT NULL,
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_at TIMESTAMP,
    executed_at TIMESTAMP,
    CONSTRAINT ck_reversion_scope CHECK (scope IN ('SMALL', 'LARGE')),
    CONSTRAINT ck_reversion_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXECUTED'))
);

CREATE TABLE IF NOT EXISTS high_risk_approvals (
    id VARCHAR(64) PRIMARY KEY,
    reversion_request_id VARCHAR(64) NOT NULL,
    approver VARCHAR(64) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    comment VARCHAR(1024),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_approval_actor UNIQUE (reversion_request_id, approver),
    CONSTRAINT fk_approval_reversion FOREIGN KEY (reversion_request_id) REFERENCES reversion_requests(id),
    CONSTRAINT ck_approval_decision CHECK (decision IN ('APPROVED', 'REJECTED'))
);

CREATE TABLE IF NOT EXISTS idempotency_records (
    idempotency_key VARCHAR(256) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    result BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_status ON users(status);
CREATE INDEX IF NOT EXISTS idx_offering_year ON course_offerings(academic_year, semester);
CREATE INDEX IF NOT EXISTS idx_enrollment_student ON enrollments(student_id);
CREATE INDEX IF NOT EXISTS idx_grade_status ON grades(status);
CREATE INDEX IF NOT EXISTS idx_history_grade ON grade_history(grade_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_actor_time ON audit_logs(actor, created_at);
CREATE INDEX IF NOT EXISTS idx_alert_status ON alerts(status, severity);
CREATE INDEX IF NOT EXISTS idx_reversion_status ON reversion_requests(status);
