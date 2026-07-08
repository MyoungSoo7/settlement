# Pull Request

## Summary of Change
<!-- Provide a clear and concise description of what this PR does -->


## Related Issue
<!-- Link to the related issue(s) -->
Closes #


## Domain Impact
<!-- Check all that apply -->
- [ ] User
- [ ] Order
- [ ] Payment
- [ ] Settlement
- [ ] Other: _______


## Architecture Impact
<!-- Check the layers affected by this change -->
- [ ] Domain Layer (core business logic)
- [ ] Application Layer (use cases/services)
- [ ] Adapter Layer (controllers, repositories, external integrations)
- [ ] Infrastructure (configuration, database migrations)


## Test Coverage
<!-- Describe the tests added/modified -->
- **Unit Tests**:
- **Integration Tests**:
- **Coverage**: __%


## Architecture Compliance Checklist
- [ ] No business logic in Controllers (only in Domain/Application layer)
- [ ] Port/Adapter separation maintained (proper dependency direction)
- [ ] Domain entities are not exposed directly in API responses
- [ ] Proper use of DTOs/Mappers between layers
- [ ] Dependencies point inward (Adapters → Application → Domain)


## Testing Checklist
- [ ] Unit tests added/updated for new functionality
- [ ] Integration tests added (if applicable)
- [ ] All tests pass locally
- [ ] Code coverage meets minimum 70% threshold
- [ ] No test dependencies on external services (proper mocking/test containers used)


## Code Quality Checklist
- [ ] Code follows project coding standards
- [ ] No new compiler warnings
- [ ] Lombok/MapStruct annotations used appropriately
- [ ] Proper exception handling implemented
- [ ] Security considerations addressed (if applicable)
- [ ] Database migrations added (if schema changes)
- [ ] API documentation updated (Swagger/OpenAPI)


## Additional Notes
<!-- Any additional context, screenshots, or information for reviewers -->
