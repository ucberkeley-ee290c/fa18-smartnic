#define WRITEQ_W                0x2000
#define WRITEQ_COUNT_W          0x2008
#define READQ_W                 0x2100
#define READQ_COUNT_W           0x2108

#define WRITEQ_R                0x2200
#define WRITEQ_COUNT_R          0x2208
#define READQ_R                 0x2300
#define READQ_COUNT_R           0x2308

#define CREECW_ENABLE           0x2400
#define CREECR_ENABLE           0x2500

// Header info
#define NUM_BEATS_IN_OFFSET     0x04
#define CR_IN_OFFSET            0x08
#define E_IN_OFFSET             0x0c
#define ECC_IN_OFFSET           0x10
#define CR_PADBYTES_IN_OFFSET   0x14
#define E_PADBYTES_IN_OFFSET    0x18
#define ECC_PADBYTES_IN_OFFSET  0x1c
#define NUM_BEATS_OUT_OFFSET    0x20
#define CR_OUT_OFFSET           0x24
#define E_OUT_OFFSET            0x28
#define ECC_OUT_OFFSET          0x2c
#define CR_PADBYTES_OUT_OFFSET  0x30
#define E_PADBYTES_OUT_OFFSET   0x34
#define ECC_PADBYTES_OUT_OFFSET 0x38
