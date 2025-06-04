# File Comparison Tool 🚀

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java Version](https://img.shields.io/badge/Java-17+-blue.svg)](https://www.oracle.com/java/technologies/javase-jdk11-downloads.html)
[![Spring Boot Version](https://img.shields.io/badge/Spring%20Boot-3.4.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue.js Version](https://img.shields.io/badge/Vue.js-3.x-green.svg)](https://vuejs.org/)
<!-- Add other relevant badges: build status, code coverage, etc. -->

A powerful and user-friendly web-based tool built with Java, Spring Boot, and Vue.js for comparing various types of files (Excel, CSV, Text, JSON) with advanced features like column ignoring, manual pairing, and detailed reporting.

![File Comparison Tool Screenshot](placeholder_screenshot.png)
<!-- TODO: Replace placeholder_screenshot.png with an actual screenshot of your tool's UI -->

## ✨ Features

*   **Intuitive Web UI:** Modern and responsive user interface built with Vue.js and Bootstrap.
*   **Multiple File Type Support:** Compare:
    *   Excel files (`.xls`, `.xlsx`)
    *   CSV files (`.csv`)
    *   Text files (`.txt`, `.log`, etc.)
    *   JSON files (line-by-line comparison after pretty-printing)
*   **Dual Drag & Drop Zones:** Easily upload files for Source 1 and Source 2.
*   **Flexible Pairing Options:**
    *   **Automatic Pairing by Sorted Index:** Sort files in each source and compare them positionally.
    *   **Manual Pairing:** Explicitly define which file from Source 1 compares against which file in Source 2 – perfect for files with different names but corresponding content.
*   **Column/Header Ignoring:**
    *   Dynamically detect columns/headers from uploaded files.
    *   Select specific columns to exclude from the comparison process for each source independently.
    *   Option to specify if the first row is a header (impacts ignore logic and output).
*   **Comprehensive Comparison Reports:**
    *   **Detailed UI Results:** View overall metrics and per-pair comparison status directly in the web interface using an interactive accordion.
    *   **Individual CSV Reports per Pair:** Each file pair comparison generates its own structured CSV report.
        *   Shows matched, mismatched, and missing lines.
        *   Outputs *kept* data into separate columns for easy filtering and analysis in spreadsheet software.
    *   **Download All Reports (ZIP):** Conveniently download all individual CSV reports for a comparison session in a single ZIP archive.
*   **Persistent Storage & History (Admin Feature):**
    *   Comparison source files and generated reports are saved to disk per session.
    *   **Admin Dashboard:** A separate web page (`/admin/dashboard`) to:
        *   View a history of all past comparisons.
        *   See key metrics (total comparisons, files processed, match/mismatch rates).
        *   Download report ZIPs from previous sessions.
*   **Robust Backend:** Built with Spring Boot, ensuring scalability and maintainability.

## 🛠️ Tech Stack

*   **Backend:**
    *   Java 11+
    *   Spring Boot (Web, Data JPA)
    *   Apache POI (for Excel parsing)
    *   OpenCSV (for CSV parsing)
    *   Jackson (for JSON handling)
    *   H2 Database (embedded, for admin logs - easily switchable)
*   **Frontend:**
    *   Vue.js 3 (Composition API, single-file HTML setup for simplicity in this example)
    *   Bootstrap 5 (for styling)
    *   Font Awesome (for icons)
*   **Build Tool:** Maven

## 🚀 Getting Started

### Prerequisites

*   Java JDK 11 or higher
*   Apache Maven 3.6+
*   Node.js and npm (only if you plan to evolve the frontend into a Vite/CLI project)

### Running the Application

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/your-username/file-comparison-tool.git
    cd file-comparison-tool
    ```

2.  **Build and Run with Maven:**
    ```bash
    mvn spring-boot:run
    ```
    The application will typically start on `http://localhost:8080`.

3.  **Access the Tool:**
    *   Main Comparison Tool: `http://localhost:8080/`
    *   Admin Dashboard: `http://localhost:8080/admin/dashboard`
    *   H2 Database Console (for development): `http://localhost:8080/h2-console`
        *   JDBC URL: `jdbc:h2:file:./file_compare_db`
        *   User Name: `sa`
        *   Password: (empty)

### Configuration

Key configurations can be found in `src/main/resources/application.properties`:

*   `server.port`: Change the application port (default is 8080).
*   `spring.servlet.multipart.max-file-size`/`max-request-size`: Configure upload limits.
*   `file.comparison.storage.base-path`: Directory where uploaded files and reports are temporarily stored. Ensure this path is writable.
*   H2 Database settings (URL, username, password).

## Screenshots

<!-- Add more screenshots showcasing different features -->
**Main Comparison Interface:**
![Main UI](placeholder_main_ui.png)

**Column Ignore Configuration:**
![Column Ignore UI](placeholder_column_ignore.png)

**Admin Dashboard:**
![Admin Dashboard](placeholder_admin_dashboard.png)

**Sample CSV Report Structure:**
![CSV Report Sample](placeholder_csv_report.png)

## 📖 Usage Guide

1.  **Navigate** to the main page (`/`).
2.  **Upload Files:** Drag and drop files into the "Source 1 Files" and "Source 2 Files" zones, or click to select them.
3.  **(Optional) Manual Pairing:** If you have multiple files and need specific pairings not based on name or sorted order, the "Manual File Pairing" section will appear. Select one file from each source's "Available" list and click "Pair Selected".
4.  **(Optional) Column Ignore:**
    *   The "Column Ignore Configuration" section will show detected columns/headers from the first valid file in each source.
    *   Check the "First row is header" box for each source if applicable. This influences how columns are identified (by name or index).
    *   Select the columns you wish to *exclude* from the comparison for each source.
5.  **(Optional) Sort Files:** Check the "Sort files by name..." box if you want files within each source to be sorted alphabetically before automatic pairing (if not manually paired). This enables positional comparison.
6.  **Compare:** Click the "Compare Files" button.
7.  **View Results:**
    *   Overall metrics will be displayed.
    *   An accordion will show results for each compared pair. Expand items to see details or line differences.
8.  **Download Reports:** Click "Download All Reports (ZIP)" to get individual CSVs for each pair.
9.  **Admin Dashboard (`/admin/dashboard`):** View history and usage statistics. Download ZIPs from past sessions.

## 🏗️ How It Works (High-Level)

1.  **File Upload:** Frontend (Vue.js) handles file selection and sends them to the Spring Boot backend.
2.  **File Storage:** Backend creates a unique session directory and stores uploaded files.
3.  **Parsing:** `FileParserService` reads Excel, CSV, Text, or JSON files. For column ignoring, it first parses into rows and columns.
4.  **Column Ignoring:** Based on user configuration, specified columns are excluded before lines are reconstructed for comparison.
5.  **Pairing Logic (`CompareService`):**
    *   Manually defined pairs are processed first.
    *   If "Sort files" is enabled, remaining files are sorted and paired by their index in the respective lists.
    *   (If "Sort files" is disabled, future enhancement could be name-based matching for remaining files).
    *   Unpaired files are identified.
6.  **Comparison:**
    *   For each pair, a column-aware comparison is performed on the *non-ignored* data.
    *   Lines are compared, and differences are noted.
7.  **Report Generation:**
    *   `CsvReportGenerator` creates structured CSV files for each pair, outputting *kept* data into separate columns.
    *   A ZIP archive of all reports is created on demand.
8.  **Logging:** Comparison metadata (file counts, match stats, execution time, etc.) is saved to the database for the admin dashboard.

## 🤝 Contributing

Contributions are welcome! If you'd like to contribute, please follow these steps:

1.  **Fork** the repository.
2.  Create a new **branch** (`git checkout -b feature/your-feature-name`).
3.  Make your changes and **commit** them (`git commit -m 'Add some feature'`).
4.  **Push** to the branch (`git push origin feature/your-feature-name`).
5.  Open a **Pull Request**.

Please make sure to update tests as appropriate and adhere to the existing code style.

##  futura Enhancements & To-Do

*   [ ] More robust client-side CSV/Excel header detection.
*   [ ] Option for different comparison strategies (e.g., key-based row matching instead of just line-by-line).
*   [ ] Advanced diff visualization in the UI (e.g., side-by-side with highlighting).
*   [ ] Support for comparing files within ZIP archives.
*   [ ] User authentication and role-based access (especially for admin features).
*   [ ] Configurable options for CSV delimiters, encodings, etc.
*   [ ] Enhanced error handling and user feedback.
*   [ ] Unit and integration tests.
*   [ ] Dockerization for easier deployment.
*   [ ] Option to ignore case or whitespace during comparison.

## 📜 License

This project is licensed under the MIT License - see the [LICENSE.md](LICENSE.md) file for details.