{
  "type": "object",
  "properties": {
    "spec": {
      "title": "Principals",
      "type": "object",
      "properties": {
        "list": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "password": {
                "type": "object",
                "properties": {
                  "type": {
                    "type": "string"
                  },
                  "value": {
                    "type": "string"
                  }
                },
                "oneOf": [
                  {
                    "properties": {
                      "type": {
                        "enum": [
                          "static",
                          "random"
                        ]
                      }
                    }
                  }
                ]
              },
              "name": {
                "type": "string"
              },
              "keytab": {
                "type": "string"
              },
              "secret": {
                "type": "object",
                "properties": {
                  "type": {
                    "type": "string"
                  },
                  "name": {
                    "type": "string"
                  }
                },
                "oneOf": [
                  {
                    "properties": {
                      "type": {
                        "enum": [
                          "Keytab",
                          "KeytabAndPassword"
                        ]
                      }
                    },
                    "required": [
                      "type"
                    ]
                  }
                ]
              }
            }
          }
        }
      }
    },
    "status": {
      "type": "object",
      "properties": {
        "processed": {
          "type": "boolean"
        },
        "lastPrincipalCount": {
          "type": "integer"
        },
        "totalPrincipalCount": {
          "type": "integer"
        },
        "error": {
          "type": "string"
        }
      },
      "required": [
        "processed",
        "lastPrincipalCount",
        "totalPrincipalCount"
      ]
    }
  }
}