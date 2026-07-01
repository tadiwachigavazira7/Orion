import React from 'react';
import { Image, StyleSheet, Text, View } from 'react-native';
import { theme } from '../../../../theme';


interface PaymentCardProps {
  cardBrand: string;
  last4: string;
  expDate: string;
}

export async function SavedPaymentCard({ cardBrand, last4, expDate }: PaymentCardProps) {


  // Conditionally resolve local asset paths safely
  const getCardLogo = () => {
    switch (cardBrand.toLowerCase()) {
      case 'visa':
        return require('../../../../../assets/images/visa_logo.png');
      case 'american express':
      case 'amex':
        return require('../../../../../assets/images/amex_logo.png');
      default:
        return null;
    }
  };



  return (
    <View style={[styles.cardContainer, { backgroundColor: theme.card === 'white' ? '#FAFAFA' : theme.card }]}>
      
      {/* Left Column Brand Asset Container */}
      <View style={styles.logoWrapper}>
        {getCardLogo() ? (
          <Image source={getCardLogo()} style={styles.cardLogo} resizeMode="contain" />
        ) : (
          <Text style={[styles.fallbackBrandText, { color: theme.text }]}>{cardBrand}</Text>
        )}
      </View>

      {/* Right Column Specifications Data Block matching image_347b4d.png */}
      <View style={styles.detailsWrapper}>
        <Text style={[styles.infoText, { color: theme.text }]}>Card Number: XX-{last4}</Text>
        <Text style={[styles.infoText, { color: theme.text }]}>Exp: {expDate}</Text>
      </View>

    </View>
  );
}



const styles = StyleSheet.create({
  cardContainer: {
    flexDirection: 'row',
    borderRadius: 20,
    paddingVertical: 16,
    paddingHorizontal: 20,
    marginBottom: 16,
    alignItems: 'center',
    width: '100%',
    height: 100,
  },
  logoWrapper: {
    width: '35%',
    alignItems: 'flex-start',
    justifyContent: 'center',
  },
  cardLogo: {
    width: 75,
    height: 40,
  },
  fallbackBrandText: {
    fontSize: 18,
    fontWeight: 'bold',
  },
  detailsWrapper: {
    width: '65%',
    justifyContent: 'center',
    paddingLeft: 10,
  },
  infoText: {
    fontSize: 13,
    fontFamily: 'serif',
    lineHeight: 18,
  },
});