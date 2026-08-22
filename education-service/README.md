# education-service

교육 과정과 강의 차시를 소유하는 DB-per-service 관리자 서비스입니다.

## Local

- HTTP: `8116` (management/actuator: `8117`)
- PostgreSQL: `lemuel_education` on host port `5451`
- Gateway: `/admin/education/**`
- Authorization: `ROLE_ADMIN`

## First-release API

- `GET/POST /admin/education/courses`
- `GET/PUT /admin/education/courses/{courseId}`
- `POST /admin/education/courses/{courseId}/publish|hide|close`
- `GET/POST /admin/education/courses/{courseId}/lessons`
- `PUT/DELETE /admin/education/courses/{courseId}/lessons/{lessonId}`
- `POST /admin/education/courses/{courseId}/lessons/reorder`

Publishing writes a `CoursePublished` event to the education schema's outbox through shared-common's transactional outbox port. Kafka publication remains the responsibility of the configured outbox publisher.
