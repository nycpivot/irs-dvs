# IRS Form 8582 Processor

Spring Boot application for processing IRS Form 8582 - Passive Activity Loss Limitations (2024).

## Overview

This application implements the calculation logic for IRS Form 8582, including:

- **Passive Activity Loss Calculations**: Calculate overall gains/losses from passive activities
- **Special Allowance for Rental Real Estate**: Calculate the $25,000 special allowance with phaseouts
- **Active Participation Rules**: Determine eligibility for special allowances
- **Publicly Traded Partnerships (PTP)**: Separate tracking of PTP activities
- **Prior Year Unallowed Losses**: Carryforward and application of prior year losses

## Key Features

- RESTful API for form calculations
- PostgreSQL database for persistence
- JPA/Hibernate for data management
- Comprehensive business logic based on 2024 IRS instructions

## Technology Stack
- Java 17
- Spring Boot 3.2.0
- Spring Data JPA
- PostgreSQL
- Lombok

## API Endpoints

### POST /api/form8582/calculate
Calculate passive activity losses and special allowances.

**Request Body**:
```json
{
  "taxpayerId": "123-45-6789",
  "taxYear": 2024,
  "filingStatus": "joint",
  "modifiedAdjustedGrossIncome": 85000,
  "passiveActivities": [
    {
      "activityName": "Rental Property A",
      "activityType": "Rental Real Estate",
      "currentYearIncome": 12000,
      "currentYearLoss": 15000,
      "priorYearUnallowedLoss": 5000,
      "activeParticipation": true,
      "formSchedule": "Schedule E"
    }
  ]
}
```

### GET /api/form8582/health
Health check endpoint.

## Database Setup

1. Create PostgreSQL database:
```sql
CREATE DATABASE form8582db;
CREATE USER irsuser WITH PASSWORD 'irspass';
GRANT ALL PRIVILEGES ON DATABASE form8582db TO irsuser;
```

2. Tables will be auto-created by Hibernate on application startup.

## Running the Application

```bash
mvn spring-boot:run
```

The application will start on `http://localhost:8080`.

## Key Business Rules Implemented

1. **Passive Activity Identification**: Trade or business activities without material participation, rental activities
2. **Special Allowance Calculation**: $25,000 max for single/joint, $12,000 for married filing separately
3. **Modified AGI Phaseout**: 50% reduction for AGI between $100,000-$150,000 ($50,000-$75,000 MFS)
4. **Active Participation**: Management decisions, approving tenants, rental terms, expenditures
5. **PTP Rules**: Separate tracking of publicly traded partnership activities

## References

- [IRS Form 8582 (2024)](https://www.irs.gov/forms-pubs/about-form-8582)
- [IRS Publication 925 - Passive Activity and At-Risk Rules](https://www.irs.gov/publications/p925)
- Internal Revenue Code Section 469

## License

This is a demonstration project based on public IRS documentation.