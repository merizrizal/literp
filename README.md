# Literp - Lightweight ERP System

A modern, cloud-native lightweight ERP system built with Vert.x, Kotlin, and reactive programming. Designed for POS-first operations with movement-based inventory management.

## 📚 Documentation

All API and implementation documentation is located in the [./docs](docs) directory:

### Quick Start
- **[docs/QUICK_START.md](docs/QUICK_START.md)** - 30-second setup guide and common tasks

### API Documentation
- **[docs/API_IMPLEMENTATION.md](docs/API_IMPLEMENTATION.md)** - Comprehensive technical guide
- **[docs/API_TESTING_GUIDE.md](docs/API_TESTING_GUIDE.md)** - Testing with curl examples for all 21 endpoints
- **[docs/ENDPOINTS_OVERVIEW.md](docs/ENDPOINTS_OVERVIEW.md)** - Visual diagrams and endpoint reference
- **[docs/README_API.md](docs/README_API.md)** - Complete index and quick links

### Implementation Details
- **[docs/IMPLEMENTATION_SUMMARY.md](docs/IMPLEMENTATION_SUMMARY.md)** - Project overview and architecture
- **[docs/VERIFICATION_CHECKLIST.md](docs/VERIFICATION_CHECKLIST.md)** - Completion validation checklist

## 🚀 Quick Start

```bash
# Build the project
./gradlew build

# Start the server
./gradlew run

# Server will listen on http://localhost:8010/api/v1
```

For detailed setup instructions, see [docs/QUICK_START.md](docs/QUICK_START.md).

## 📊 Project Overview

**Technology Stack:**
- Vert.x 5.0.8 (reactive, event-driven)
- Kotlin 2.3.10 on Java 25
- RxJava3 (non-blocking async)
- PostgreSQL (async driver)
- OpenAPI 3.0

**Features:**
- 21 REST API endpoints across 4 resources
- Pagination, filtering, and sorting
- Soft delete for audit trails
- JSONB fields for extensibility
- Connection pooling and async I/O

## 📁 Project Structure

```
literp/
├── docs/                    # All documentation
│   ├── QUICK_START.md
│   ├── API_IMPLEMENTATION.md
│   ├── API_TESTING_GUIDE.md
│   ├── ENDPOINTS_OVERVIEW.md
│   ├── README_API.md
│   ├── IMPLEMENTATION_SUMMARY.md
│   └── VERIFICATION_CHECKLIST.md
├── src/
│   └── main/kotlin/com/literp/
│       ├── App.kt
│       ├── config/
│       ├── db/
│       ├── repository/
│       └── verticle/
├── python/
│   └── database/migration/  # Alembic migrations
├── docker/                  # Docker configuration
├── api_collections/         # OpenAPI specifications
└── build.gradle.kts         # Gradle build config
```

## 🔧 API Endpoints (21 Total)

**Unit of Measure (5)**
- GET/POST /uom
- GET/PUT/DELETE /uom/{uomId}

**Products (5)**
- GET/POST /products
- GET/PUT/DELETE /products/{productId}

**Product Variants (5)**
- GET/POST /products/{productId}/variants
- GET/PUT/DELETE /products/{productId}/variants/{variantId}

**Locations (6)**
- GET/POST /locations
- GET /locations/{locationId}
- GET /locations/by-code/{code}
- PUT /locations/{locationId}
- DELETE /locations/{locationId}

For detailed endpoint documentation, see [docs/API_TESTING_GUIDE.md](docs/API_TESTING_GUIDE.md).

## 💾 Database Setup

```bash
# Apply migrations
cd python/database/migration
alembic upgrade head

# Start infrastructure
cd docker
DIR=pgsql make env-up
```

PostgreSQL connections: `postgres://postgres:postgres@localhost:5432/literp`

## 🧪 Testing

All endpoints can be tested with curl. See [docs/API_TESTING_GUIDE.md](docs/API_TESTING_GUIDE.md) for examples.

Example:
```bash
curl http://localhost:8010/api/v1/locations | jq
```

## 📖 Documentation Hierarchy

1. **Getting Started**: Read [docs/QUICK_START.md](docs/QUICK_START.md)
2. **API Details**: Review [docs/API_IMPLEMENTATION.md](docs/API_IMPLEMENTATION.md)
3. **Testing Guide**: Use [docs/API_TESTING_GUIDE.md](docs/API_TESTING_GUIDE.md) for examples
4. **Visual Reference**: See [docs/ENDPOINTS_OVERVIEW.md](docs/ENDPOINTS_OVERVIEW.md)
5. **Complete Index**: Check [docs/README_API.md](docs/README_API.md)

## 🔐 Security

- SQL injection protection (parameterized queries)
- Input validation and error handling
- Standard error responses

Future enhancements:
- JWT authentication
- Role-based authorization
- Rate limiting
- HTTPS/SSL support

## 📝 Key Features

✅ 21 fully functional REST endpoints
✅ Pagination and sorting
✅ Advanced filtering (locations)
✅ Soft deletes for audit trails
✅ JSONB fields for extensibility
✅ Async non-blocking I/O
✅ Connection pooling
✅ Comprehensive error handling
✅ Complete API documentation

## 🛠️ Development

The implementation includes:
- Repository pattern for data access
- Vert.x OpenAPI router integration
- RxJava3 for reactive operations
- Kotlin null-safety features
- Proper logging and error handling

## 📞 Support

For implementation details, architecture guidance, and technical questions, refer to:
- [docs/API_IMPLEMENTATION.md](docs/API_IMPLEMENTATION.md) - Technical architecture
- [docs/ENDPOINTS_OVERVIEW.md](docs/ENDPOINTS_OVERVIEW.md) - Visual diagrams
- [docs/VERIFICATION_CHECKLIST.md](docs/VERIFICATION_CHECKLIST.md) - Validation checklist

## 📄 License

Literp - Lightweight ERP System

---

**Status**: ✅ Production Ready
**Version**: 1.0.0
**Last Updated**: February 2026
**Total Endpoints**: 21
**Technology**: Vert.x 5.0.8 + Kotlin 2.3.10