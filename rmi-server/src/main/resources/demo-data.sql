MERGE INTO roles (id, code, name) KEY(id) VALUES
 (1, 'ADMIN', '系统管理员'), (2, 'TEACHER', '教师'), (3, 'STUDENT', '学生'),
 (4, 'AUDITOR', '审计员'), (5, 'GATEWAY', '认证网关'), (6, 'SYSTEM', '系统审计主体'),
 (7, 'ANALYTICS', '受限统计主体');

MERGE INTO permissions (id, code, name) KEY(id) VALUES
 (1, 'COURSE_READ', '读取课程'), (2, 'GRADE_READ', '读取授课班级成绩'),
 (3, 'GRADING_SCHEME_WRITE', '维护评分方案'), (4, 'GRADE_DRAFT_WRITE', '保存成绩草稿'),
 (5, 'GRADE_SUBMIT', '提交成绩'), (6, 'GRADE_WITHDRAW', '撤回成绩'),
 (7, 'GRADE_ANALYTICS_READ', '读取成绩分析'), (8, 'GRADE_HISTORY_READ', '读取成绩历史'),
 (9, 'RISK_ANALYZE', '执行风险分析'), (10, 'GRADE_SELF_READ', '读取本人课程成绩'),
 (11, 'RISK_SELF_ANALYZE', '读取本人风险分析'), (12, 'USER_MANAGE', '用户管理'),
 (13, 'ORG_MANAGE', '组织管理'), (14, 'PERMISSION_MANAGE', '权限管理'),
 (15, 'GRADE_REVERT_SMALL', '小范围成绩撤销'), (16, 'GRADE_REVERT_REQUEST', '发起大范围撤销'),
 (17, 'GRADE_REVERT_APPROVE', '审批大范围撤销'), (18, 'GRADE_RESTORE_ORIGINAL', '恢复原始加密成绩'),
 (19, 'AUDIT_READ', '读取审计日志'), (20, 'INTEGRITY_VERIFY', '校验完整性账本'),
 (21, 'ALERT_MANAGE', '安全告警管理');

MERGE INTO orgs (id, code, name, parent_id) KEY(id) VALUES
 (1, 'CHD', '长安大学', NULL), (2, 'CS', '信息工程学院', 1),
 (3, 'MATH', '理学院', 1), (4, 'ADMIN', '教务处', 1);

MERGE INTO users (id, username, password_hash, display_name, email, status, student_id, teacher_id, created_at, updated_at) KEY(id) VALUES
 (1, 'admin', '$2y$12$l7Gr85JcG/jTwd1rdPaH2OvuHQkqtXtURRsT8iaz3J9Wqxxz919Im', '系统管理员', 'admin@example.edu.cn', 'ACTIVE', NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (2, 'teacher01', '$2y$12$l7Gr85JcG/jTwd1rdPaH2OvuHQkqtXtURRsT8iaz3J9Wqxxz919Im', '张老师', 'teacher01@example.edu.cn', 'ACTIVE', NULL, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (3, 'teacher02', '$2y$12$l7Gr85JcG/jTwd1rdPaH2OvuHQkqtXtURRsT8iaz3J9Wqxxz919Im', '李老师', 'teacher02@example.edu.cn', 'ACTIVE', NULL, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (4, 'student01', '$2y$12$l7Gr85JcG/jTwd1rdPaH2OvuHQkqtXtURRsT8iaz3J9Wqxxz919Im', '王同学', 'student01@example.edu.cn', 'ACTIVE', 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (5, 'student02', '$2y$12$l7Gr85JcG/jTwd1rdPaH2OvuHQkqtXtURRsT8iaz3J9Wqxxz919Im', '赵同学', 'student02@example.edu.cn', 'ACTIVE', 2, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (6, 'student03', '$2y$12$l7Gr85JcG/jTwd1rdPaH2OvuHQkqtXtURRsT8iaz3J9Wqxxz919Im', '陈同学', 'student03@example.edu.cn', 'ACTIVE', 3, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (7, 'student04', '$2y$12$l7Gr85JcG/jTwd1rdPaH2OvuHQkqtXtURRsT8iaz3J9Wqxxz919Im', '刘同学', 'student04@example.edu.cn', 'ACTIVE', 4, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (8, 'student05', '$2y$12$l7Gr85JcG/jTwd1rdPaH2OvuHQkqtXtURRsT8iaz3J9Wqxxz919Im', '孙同学', 'student05@example.edu.cn', 'ACTIVE', 5, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (9, 'student06', '$2y$12$l7Gr85JcG/jTwd1rdPaH2OvuHQkqtXtURRsT8iaz3J9Wqxxz919Im', '周同学', 'student06@example.edu.cn', 'ACTIVE', 6, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (10, 'auditor', '$2y$12$l7Gr85JcG/jTwd1rdPaH2OvuHQkqtXtURRsT8iaz3J9Wqxxz919Im', '安全审计员', 'auditor@example.edu.cn', 'ACTIVE', NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (11, 'web-backend', '$2y$12$l7Gr85JcG/jTwd1rdPaH2OvuHQkqtXtURRsT8iaz3J9Wqxxz919Im', 'Web 后端服务', NULL, 'ACTIVE', NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (12, 'admin02', '$2y$12$l7Gr85JcG/jTwd1rdPaH2OvuHQkqtXtURRsT8iaz3J9Wqxxz919Im', '第二管理员', 'admin02@example.edu.cn', 'ACTIVE', NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

MERGE INTO user_roles (user_id, role_id) KEY(user_id, role_id) VALUES
 (1,1), (2,2), (3,2), (4,3), (5,3), (6,3), (7,3), (8,3), (9,3),
 (10,4), (11,5), (11,6), (11,7), (12,1);

MERGE INTO role_permissions (role_id, permission_id) KEY(role_id, permission_id)
 SELECT 1, id FROM permissions;
MERGE INTO role_permissions (role_id, permission_id) KEY(role_id, permission_id) VALUES
 (2,1),(2,2),(2,3),(2,4),(2,5),(2,6),(2,7),(2,8),(2,9),
 (3,10),(3,11),(4,19),(4,20),(4,21);

MERGE INTO user_permissions (user_id, permission_id, granted, granted_by, created_at) KEY(user_id, permission_id) VALUES
 (2, 15, TRUE, 'admin', CURRENT_TIMESTAMP), (3, 15, FALSE, 'admin', CURRENT_TIMESTAMP);

MERGE INTO students (id, student_no, user_id, name, gender, admission_year, class_name, major, status) KEY(id) VALUES
 (1,'20230001',4,'王同学','男',2023,'软件2301','软件工程','ACTIVE'),
 (2,'20230002',5,'赵同学','女',2023,'软件2301','软件工程','ACTIVE'),
 (3,'20230003',6,'陈同学','男',2023,'软件2301','软件工程','ACTIVE'),
 (4,'20240001',7,'刘同学','女',2024,'计科2401','计算机科学与技术','ACTIVE'),
 (5,'20240002',8,'孙同学','男',2024,'计科2401','计算机科学与技术','ACTIVE'),
 (6,'20250001',9,'周同学','女',2025,'数据2501','数据科学与大数据技术','ACTIVE');

MERGE INTO teachers (id, teacher_no, user_id, name, title, org_id, status) KEY(id) VALUES
 (1,'T0001',2,'张老师','副教授',2,'ACTIVE'),
 (2,'T0002',3,'李老师','讲师',2,'ACTIVE');

MERGE INTO courses (id, course_code, name, credit, hours, org_id, status) KEY(id) VALUES
 (1,'CS101','Java 程序设计',3.5,64,2,'ACTIVE'),
 (2,'CS201','数据结构',4.0,72,2,'ACTIVE'),
 (3,'CS301','计算机网络与安全',3.5,64,2,'ACTIVE'),
 (4,'CS302','数据库系统',3.0,56,2,'ACTIVE'),
 (5,'CS401','分布式系统',3.0,48,2,'ACTIVE'),
 (6,'MA201','离散数学',3.0,56,3,'ACTIVE');

MERGE INTO course_offerings (id,course_id,teacher_id,academic_year,semester,class_name,capacity,status) KEY(id) VALUES
 (1,1,1,'2023-2024',1,'软件2301',60,'CLOSED'),
 (2,2,2,'2023-2024',2,'软件2301',60,'CLOSED'),
 (3,2,2,'2023-2024',2,'计科2301',60,'CLOSED'),
 (4,3,1,'2024-2025',1,'软件2301',60,'CLOSED'),
 (5,4,2,'2024-2025',2,'软件2301',60,'CLOSED'),
 (6,1,1,'2024-2025',1,'计科2401',60,'CLOSED'),
 (7,5,1,'2025-2026',1,'软件2301',60,'OPEN'),
 (8,3,1,'2025-2026',1,'计科2401',60,'OPEN'),
 (9,6,2,'2025-2026',1,'数据2501',60,'OPEN'),
 (10,1,1,'2025-2026',1,'跨专业数据2501',60,'CLOSED'),
 (11,1,1,'2026-2027',1,'软件2301进阶班',60,'OPEN');

MERGE INTO enrollments (id,offering_id,student_id,status,enrolled_at) KEY(id) VALUES
 (1,1,1,'COMPLETED','2023-09-01 08:00:00'),(2,1,2,'COMPLETED','2023-09-01 08:01:00'),
 (3,1,3,'COMPLETED','2023-09-01 08:02:00'),(4,2,1,'COMPLETED','2024-02-25 08:00:00'),
 (5,2,2,'COMPLETED','2024-02-25 08:01:00'),(6,2,3,'COMPLETED','2024-02-25 08:02:00'),
 (7,4,1,'COMPLETED','2024-09-01 08:00:00'),(8,4,2,'COMPLETED','2024-09-01 08:01:00'),
 (9,4,3,'COMPLETED','2024-09-01 08:02:00'),(10,5,1,'COMPLETED','2025-02-24 08:00:00'),
 (11,5,2,'COMPLETED','2025-02-24 08:01:00'),(12,5,3,'COMPLETED','2025-02-24 08:02:00'),
 (13,6,4,'COMPLETED','2024-09-01 08:00:00'),(14,6,5,'COMPLETED','2024-09-01 08:01:00'),
 (15,7,1,'ENROLLED','2025-09-01 08:00:00'),(16,7,2,'ENROLLED','2025-09-01 08:01:00'),
 (17,7,3,'ENROLLED','2025-09-01 08:02:00'),(18,8,4,'ENROLLED','2025-09-01 08:03:00'),
 (19,8,5,'ENROLLED','2025-09-01 08:04:00'),(20,9,6,'ENROLLED','2025-09-01 08:05:00'),
 (21,10,6,'COMPLETED','2025-09-01 08:06:00'),(22,10,4,'COMPLETED','2025-09-01 08:07:00'),
 (23,10,5,'COMPLETED','2025-09-01 08:08:00'),
 (24,11,1,'ENROLLED','2026-08-28 08:00:00'),(25,11,2,'ENROLLED','2026-08-28 08:01:00'),
 (26,11,3,'ENROLLED','2026-08-28 08:02:00');

MERGE INTO grading_schemes (id,offering_id,name,total_weight,version,status) KEY(id) VALUES
 (1,1,'平时 30% + 实验 20% + 期末 50%',100.000,1,'ARCHIVED'),
 (2,2,'作业 20% + 实验 30% + 期末 50%',100.000,1,'ARCHIVED'),
 (3,3,'作业 20% + 实验 30% + 期末 50%',100.000,1,'ARCHIVED'),
 (4,4,'实验 40% + 项目 20% + 期末 40%',100.000,1,'ARCHIVED'),
 (5,5,'实验 30% + 项目 30% + 期末 40%',100.000,1,'ARCHIVED'),
 (6,6,'平时 30% + 实验 20% + 期末 50%',100.000,1,'ARCHIVED'),
 (7,7,'论文 20% + 项目 40% + 期末 40%',100.000,1,'ACTIVE'),
 (8,8,'实验 40% + 项目 30% + 期末 30%',100.000,1,'ACTIVE'),
 (9,9,'作业 30% + 阶段测试 30% + 期末 40%',100.000,1,'ACTIVE'),
 (10,10,'平时 30% + 实验 20% + 期末 50%',100.000,1,'ARCHIVED'),
 (11,11,'平时 30% + 实验 20% + 期末 50%',100.000,1,'ACTIVE');

MERGE INTO grading_weights (id,scheme_id,item_code,item_name,weight,max_score,sort_order) KEY(id) VALUES
 (1,1,'DAILY','平时成绩',30,100,1),(2,1,'LAB','实验成绩',20,100,2),(3,1,'FINAL','期末考试',50,100,3),
 (4,2,'HOMEWORK','作业',20,100,1),(5,2,'LAB','实验',30,100,2),(6,2,'FINAL','期末考试',50,100,3),
 (7,3,'HOMEWORK','作业',20,100,1),(8,3,'LAB','实验',30,100,2),(9,3,'FINAL','期末考试',50,100,3),
 (10,4,'LAB','安全实验',40,100,1),(11,4,'PROJECT','课程项目',20,100,2),(12,4,'FINAL','期末考试',40,100,3),
 (13,5,'LAB','数据库实验',30,100,1),(14,5,'PROJECT','课程设计',30,100,2),(15,5,'FINAL','期末考试',40,100,3),
 (16,6,'DAILY','平时成绩',30,100,1),(17,6,'LAB','实验成绩',20,100,2),(18,6,'FINAL','期末考试',50,100,3),
 (19,7,'PAPER','论文阅读',20,100,1),(20,7,'PROJECT','分布式项目',40,100,2),(21,7,'FINAL','期末考试',40,100,3),
 (22,8,'LAB','安全实验',40,100,1),(23,8,'PROJECT','课程项目',30,100,2),(24,8,'FINAL','期末考试',30,100,3),
 (25,9,'HOMEWORK','作业',30,100,1),(26,9,'MIDTERM','阶段测试',30,100,2),(27,9,'FINAL','期末考试',40,100,3),
 (28,10,'DAILY','平时成绩',30,100,1),(29,10,'LAB','实验成绩',20,100,2),(30,10,'FINAL','期末考试',50,100,3),
 (31,11,'DAILY','平时成绩',30,100,1),(32,11,'LAB','实验成绩',20,100,2),(33,11,'FINAL','期末考试',50,100,3);

MERGE INTO grades (id,enrollment_id,scheme_id,score_ciphertext,score_nonce,score_integrity,key_version,status,version,submitted_by,submitted_at,updated_at) KEY(id) VALUES
 (1,1,1,'','','',1,'SUBMITTED',1,2,'2024-01-15 10:00:00','2024-01-15 10:00:00'),
 (2,2,1,'','','',1,'SUBMITTED',1,2,'2024-01-15 10:01:00','2024-01-15 10:01:00'),
 (3,3,1,'','','',1,'SUBMITTED',1,2,'2024-01-15 10:02:00','2024-01-15 10:02:00'),
 (4,4,2,'','','',1,'SUBMITTED',1,3,'2024-06-25 10:00:00','2024-06-25 10:00:00'),
 (5,5,2,'','','',1,'SUBMITTED',1,3,'2024-06-25 10:01:00','2024-06-25 10:01:00'),
 (6,6,2,'','','',1,'SUBMITTED',1,3,'2024-06-25 10:02:00','2024-06-25 10:02:00'),
 (7,7,4,'','','',1,'SUBMITTED',1,2,'2025-01-15 10:00:00','2025-01-15 10:00:00'),
 (8,8,4,'','','',1,'SUBMITTED',1,2,'2025-01-15 10:01:00','2025-01-15 10:01:00'),
 (9,9,4,'','','',1,'SUBMITTED',1,2,'2025-01-15 10:02:00','2025-01-15 10:02:00'),
 (10,10,5,'','','',1,'SUBMITTED',1,3,'2025-06-25 10:00:00','2025-06-25 10:00:00'),
 (11,11,5,'','','',1,'SUBMITTED',1,3,'2025-06-25 10:01:00','2025-06-25 10:01:00'),
 (12,12,5,'','','',1,'SUBMITTED',1,3,'2025-06-25 10:02:00','2025-06-25 10:02:00'),
 (13,13,6,'','','',1,'SUBMITTED',1,2,'2025-01-15 10:03:00','2025-01-15 10:03:00'),
 (14,14,6,'','','',1,'SUBMITTED',1,2,'2025-01-15 10:04:00','2025-01-15 10:04:00'),
 (15,15,7,'','','',1,'DRAFT',1,2,NULL,'2025-10-10 09:00:00'),
 (16,16,7,'','','',1,'DRAFT',1,2,NULL,'2025-10-10 09:01:00'),
 (17,17,7,'','','',1,'DRAFT',1,2,NULL,'2025-10-10 09:02:00'),
 (18,21,10,'','','',1,'SUBMITTED',1,2,'2026-01-15 10:00:00','2026-01-15 10:00:00'),
 (19,22,10,'','','',1,'SUBMITTED',1,2,'2026-01-15 10:01:00','2026-01-15 10:01:00'),
 (20,23,10,'','','',1,'SUBMITTED',1,2,'2026-01-15 10:02:00','2026-01-15 10:02:00'),
 (21,24,11,'','','',1,'SUBMITTED',1,2,'2026-08-30 10:00:00','2026-08-30 10:00:00'),
 (22,25,11,'','','',1,'DRAFT',1,2,NULL,'2026-08-30 10:01:00'),
 (23,26,11,'','','',1,'DRAFT',1,2,NULL,'2026-08-30 10:02:00');

MERGE INTO grade_analyses (id,offering_id,average_score,max_score,min_score,pass_rate,distribution_json,analysis_text,generated_at,updated_by,updated_at) KEY(id) VALUES
 (1,1,85.67,92,78,1.000,'{"A":1,"B":2,"C":0,"D":0,"F":0}','整体表现稳定','2024-01-16 08:00:00','teacher01','2024-01-16 08:00:00'),
 (2,2,82.33,90,72,1.000,'{"A":1,"B":1,"C":1,"D":0,"F":0}','数据结构基础良好','2024-06-26 08:00:00','teacher02','2024-06-26 08:00:00'),
 (3,4,87.00,95,79,1.000,'{"A":2,"B":1,"C":0,"D":0,"F":0}','安全实验完成度高','2025-01-16 08:00:00','teacher01','2025-01-16 08:00:00'),
 (4,5,84.00,91,76,1.000,'{"A":1,"B":2,"C":0,"D":0,"F":0}','项目成绩分布正常','2025-06-26 08:00:00','teacher02','2025-06-26 08:00:00'),
 (5,6,88.50,93,84,1.000,'{"A":1,"B":1,"C":0,"D":0,"F":0}','计科班 Java 掌握良好','2025-01-16 08:30:00','teacher01','2025-01-16 08:30:00'),
 (6,10,75.67,89,60,1.000,'{"A":0,"B":1,"C":1,"D":1,"F":0}','三学年同课程样本，含补考与分项离群','2026-01-16 08:30:00','teacher01','2026-01-16 08:30:00'),
 (7,11,86.00,91,80,1.000,'{"A":1,"B":2,"C":0,"D":0,"F":0}','当前学期滚动分析','2026-08-31 09:00:00','teacher01','2026-08-31 09:00:00');

MERGE INTO alerts (id,type,severity,message,status,related_table,related_id,created_at,resolved_at) KEY(id) VALUES
 (1,'LOGIN_ANOMALY','MEDIUM','演示：连续登录失败触发的安全告警','RESOLVED','users','student03','2025-03-01 09:00:00','2025-03-01 10:00:00'),
 (2,'GRADE_OUTLIER','HIGH','演示：成绩批量变化等待复核','OPEN','course_offerings','7','2025-10-12 14:00:00',NULL);

MERGE INTO grade_exceptions (id,enrollment_id,type,description,status,reported_by,handled_by,created_at,handled_at) KEY(id) VALUES
 (1,8,'SCORE_REVIEW','实验成绩与提交记录不一致，请复核。','RESOLVED','student02','teacher01','2025-01-17 11:00:00','2025-01-18 09:00:00'),
 (2,16,'MISSING_ITEM','课程项目成绩尚未录入。','OPEN','student02',NULL,'2025-10-13 11:00:00',NULL);

MERGE INTO audit_logs (id,request_id,actor,operation,table_name,record_key,success,detail,created_at) KEY(id) VALUES
 (1,'seed-001','system','SEED','database','initial-demo-data',TRUE,'初始化三学年演示数据',CURRENT_TIMESTAMP);
