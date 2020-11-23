{
  "type": "object",
  "properties": {
    "spec": {
      "title": "KrbServer",
      "type": "object",
      "properties": {
        "realm": {
          "type": "string"
        }        
      }
    },
    "status": {
      "type": "object",
      "properties": {
        "processed": {
          "type": "boolean"
        },        
        "error": {
          "type": "string"
        }
      },
      "required": [
        "processed"
      ]
    }
  }
}