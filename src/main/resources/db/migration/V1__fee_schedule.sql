-- Reference data the scrubber rules check claims against (spec §4).
-- All codes are synthetic; see README "Code systems".

CREATE TABLE payer (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE fee_schedule_procedure (
    system      TEXT NOT NULL,
    code        TEXT NOT NULL,
    description TEXT NOT NULL,
    PRIMARY KEY (system, code)
);

-- Diagnoses that clinically indicate a procedure (ClinicalIndicationRule).
CREATE TABLE fee_schedule_indication (
    procedure_system TEXT NOT NULL,
    procedure_code   TEXT NOT NULL,
    diagnosis_system TEXT NOT NULL,
    diagnosis_code   TEXT NOT NULL,
    PRIMARY KEY (procedure_system, procedure_code, diagnosis_system, diagnosis_code),
    FOREIGN KEY (procedure_system, procedure_code) REFERENCES fee_schedule_procedure (system, code)
);

-- v3-ActCode encounter classes a procedure may be billed in (CareSettingRule).
CREATE TABLE fee_schedule_setting (
    procedure_system TEXT NOT NULL,
    procedure_code   TEXT NOT NULL,
    encounter_class  TEXT NOT NULL,
    PRIMARY KEY (procedure_system, procedure_code, encounter_class),
    FOREIGN KEY (procedure_system, procedure_code) REFERENCES fee_schedule_procedure (system, code)
);

-- Mutually exclusive procedures (BundlingRule). Each unordered pair is stored once, in canonical order.
CREATE TABLE fee_schedule_conflict (
    first_system  TEXT NOT NULL,
    first_code    TEXT NOT NULL,
    second_system TEXT NOT NULL,
    second_code   TEXT NOT NULL,
    PRIMARY KEY (first_system, first_code, second_system, second_code),
    FOREIGN KEY (first_system, first_code) REFERENCES fee_schedule_procedure (system, code),
    FOREIGN KEY (second_system, second_code) REFERENCES fee_schedule_procedure (system, code),
    CHECK ((first_system, first_code) < (second_system, second_code))
);

INSERT INTO payer (code, name) VALUES
    ('ACME-HEALTH', 'Acme Health Insurance'),
    ('NORTHWIND-MUTUAL', 'Northwind Mutual');

INSERT INTO fee_schedule_procedure (system, code, description)
SELECT 'https://openclaim.example.org/CodeSystem/procedure', code, description
FROM (VALUES
    ('PROC-CONSULT', 'General consultation'),
    ('PROC-SPIRO', 'Spirometry, full'),
    ('PROC-SPIRO-LTD', 'Spirometry, limited'),
    ('PROC-ECG', 'Electrocardiogram'),
    ('PROC-XRAY-WRIST', 'Radiograph, wrist'),
    ('PROC-CAST', 'Application of cast'),
    ('PROC-APPENDECTOMY', 'Appendectomy')
) AS p (code, description);

INSERT INTO fee_schedule_indication (procedure_system, procedure_code, diagnosis_system, diagnosis_code)
SELECT 'https://openclaim.example.org/CodeSystem/procedure', procedure_code,
       'https://openclaim.example.org/CodeSystem/diagnosis', diagnosis_code
FROM (VALUES
    ('PROC-CONSULT', 'DX-ASTHMA'),
    ('PROC-CONSULT', 'DX-COPD'),
    ('PROC-CONSULT', 'DX-HYPERTENSION'),
    ('PROC-CONSULT', 'DX-FRACTURE-WRIST'),
    ('PROC-CONSULT', 'DX-APPENDICITIS'),
    ('PROC-SPIRO', 'DX-ASTHMA'),
    ('PROC-SPIRO', 'DX-COPD'),
    ('PROC-SPIRO-LTD', 'DX-ASTHMA'),
    ('PROC-SPIRO-LTD', 'DX-COPD'),
    ('PROC-ECG', 'DX-HYPERTENSION'),
    ('PROC-XRAY-WRIST', 'DX-FRACTURE-WRIST'),
    ('PROC-CAST', 'DX-FRACTURE-WRIST'),
    ('PROC-APPENDECTOMY', 'DX-APPENDICITIS')
) AS i (procedure_code, diagnosis_code);

INSERT INTO fee_schedule_setting (procedure_system, procedure_code, encounter_class)
SELECT 'https://openclaim.example.org/CodeSystem/procedure', procedure_code, encounter_class
FROM (VALUES
    ('PROC-CONSULT', 'AMB'), ('PROC-CONSULT', 'IMP'), ('PROC-CONSULT', 'SS'), ('PROC-CONSULT', 'EMER'),
    ('PROC-SPIRO', 'AMB'), ('PROC-SPIRO', 'IMP'),
    ('PROC-SPIRO-LTD', 'AMB'),
    ('PROC-ECG', 'AMB'), ('PROC-ECG', 'IMP'), ('PROC-ECG', 'EMER'),
    ('PROC-XRAY-WRIST', 'AMB'), ('PROC-XRAY-WRIST', 'IMP'), ('PROC-XRAY-WRIST', 'EMER'),
    ('PROC-CAST', 'AMB'), ('PROC-CAST', 'EMER'),
    ('PROC-APPENDECTOMY', 'IMP'), ('PROC-APPENDECTOMY', 'SS')
) AS s (procedure_code, encounter_class);

INSERT INTO fee_schedule_conflict (first_system, first_code, second_system, second_code) VALUES
    ('https://openclaim.example.org/CodeSystem/procedure', 'PROC-SPIRO',
     'https://openclaim.example.org/CodeSystem/procedure', 'PROC-SPIRO-LTD');
