package edu.chd.practice.web.service;

import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.SecuritySupport;
import edu.chd.practice.web.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ResourceAccessService extends RemoteTableSupport {
    private final AuditService auditService;

    public ResourceAccessService(RemoteDataGateway gateway, AuditService auditService) {
        super(gateway);
        this.auditService = auditService;
    }

    public Map<String, String> requireTeachingOffering(String offeringId, String permission) {
        return requireTeachingOffering(offeringId, permission, null);
    }

    public Map<String, String> requireClosedTeachingOffering(String offeringId, String permission) {
        return requireTeachingOffering(offeringId, permission, "CLOSED");
    }

    private Map<String, String> requireTeachingOffering(String offeringId, String permission,
                                                        String requiredStatus) {
        UserPrincipal principal = requirePermission(permission);
        Map<String, String> teacher = requireOne("teachers", List.of("id", "org_id"),
                List.of(Filter.of("user_id", FilterOperator.EQ, principal.id())),
                "TEACHER_PROFILE_NOT_FOUND", "教师档案不存在");
        Map<String, String> offering;
        try {
            List<Filter> filters = requiredStatus == null
                    ? List.of(Filter.of("id", FilterOperator.EQ, offeringId))
                    : List.of(Filter.of("id", FilterOperator.EQ, offeringId),
                            Filter.of("status", FilterOperator.EQ, requiredStatus));
            offering = requireOne("course_offerings",
                    List.of("id", "course_id", "teacher_id", "academic_year", "semester", "class_name", "status"),
                    filters,
                    "OFFERING_NOT_FOUND", "开课记录不存在");
        } catch (ApiException exception) {
            if (isRmiOwnershipDenial(exception)) {
                auditService.ownershipDenied("course_offerings", offeringId);
            }
            throw exception;
        }
        if (!teacher.get("id").equals(offering.get("teacher_id"))) {
            auditService.ownershipDenied("course_offerings", offeringId);
            throw new ApiException(HttpStatus.FORBIDDEN, "RESOURCE_NOT_OWNED", "该课程不由当前教师授课");
        }
        return offering;
    }

    public Map<String, String> requireOpenTeachingOffering(String offeringId, String permission) {
        Map<String, String> offering = requireTeachingOffering(offeringId, permission);
        if (!"OPEN".equals(offering.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "OFFERING_CLOSED", "已结课课程不可再修改成绩");
        }
        return offering;
    }

    public Map<String, String> requireStudentProfile() {
        UserPrincipal principal = requirePermission("GRADE_SELF_READ");
        return requireOne("students", List.of("id", "student_no", "name", "major", "class_name"),
                List.of(Filter.of("user_id", FilterOperator.EQ, principal.id())),
                "STUDENT_PROFILE_NOT_FOUND", "学生档案不存在");
    }

    public void requireStudentEnrolledInOffering(String studentId, String offeringId) {
        if (one("enrollments", List.of("id"), List.of(
                Filter.of("student_id", FilterOperator.EQ, studentId),
                Filter.of("offering_id", FilterOperator.EQ, offeringId))) == null) {
            auditService.ownershipDenied("enrollments", offeringId + ":" + studentId);
            throw new ApiException(HttpStatus.FORBIDDEN, "STUDENT_NOT_ENROLLED", "学生未选修该课程");
        }
    }

    public void requireStudentEnrolledInCourse(String studentId, String courseId) {
        List<Map<String, String>> enrollments = rows("enrollments", List.of("offering_id"),
                List.of(Filter.of("student_id", FilterOperator.EQ, studentId)), List.of(), 0, 500);
        List<String> offeringIds = enrollments.stream().map(row -> row.get("offering_id"))
                .filter(java.util.Objects::nonNull).distinct().toList();
        boolean enrolled = !offeringIds.isEmpty() && one("course_offerings", List.of("id"), List.of(
                new Filter("id", FilterOperator.IN, offeringIds),
                Filter.of("course_id", FilterOperator.EQ, courseId))) != null;
        if (!enrolled) {
            auditService.ownershipDenied("courses", courseId + ":" + studentId);
            throw new ApiException(HttpStatus.FORBIDDEN, "STUDENT_NOT_ENROLLED", "你未修读该课程");
        }
    }

    public UserPrincipal requirePermission(String permission) {
        UserPrincipal principal = SecuritySupport.principal();
        if (!principal.hasPermission(permission)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_REQUIRED", "缺少权限: " + permission);
        }
        return principal;
    }

    public UserPrincipal requireAnyPermission(String... permissions) {
        UserPrincipal principal = SecuritySupport.principal();
        for (String permission : permissions) {
            if (principal.hasPermission(permission)) {
                return principal;
            }
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_REQUIRED",
                "缺少任一所需权限: " + String.join(", ", permissions));
    }

    static boolean isRmiOwnershipDenial(ApiException exception) {
        return exception.status() == HttpStatus.FORBIDDEN
                && "RMI_ACCESS_DENIED".equals(exception.code());
    }
}
