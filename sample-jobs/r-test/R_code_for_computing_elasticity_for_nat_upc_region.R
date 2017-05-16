library(nlme)
library(data.table)
print("loading data")
print(getwd())
## loading data

data <- read.table("sales_for_0008147569241_MA_USA.dat", header=TRUE, sep="|"); 

data <- aggregate(. ~ NAT_UPC+STORE_NUMBER+SUBCATEGORY_ID+REGION_ABBR+COUNTRY_ABBR, data=data, FUN=sum)

names(data)[names(data)=="SALE_VALUE_USD"] <- "price"; 
names(data)[names(data)=="SALE_UNITS"] <- "volume"; 
names(data)[names(data)=="NAT_UPC"] <- "upc";
names(data)[names(data)=="REGION_ABBR"] <- "region"; 
data[["price"]] <- data[["price"]]/data[["volume"]]; 

data[["log.price"]] <- log(data[["price"]], 2)
data[["log.volume"]] <- log(data[["volume"]], 2)

# fixed effect forumla 
fixed <- log.volume ~ log.price;
# random effect formula
random <- ~ log.price | upc/region;
# elasticities for upc region
spree.model <- lme(fixed=fixed, data=data, random=random, method="REML"); 
spree.coeff <- coef(spree.model,level=1); # national UPC elasticities
nat_upc_region_country_elasticity <- spree.model$coefficients$fixed[2];#nat_upc_region_elasticity 
nat_upc_region_country_elasticity 
